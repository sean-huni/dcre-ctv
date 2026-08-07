package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M10 T15 (SCRUM-78, Sean directive: key on MANDATE_REF): the DC-flow projection
 * gate END TO END through the real {@code ctvJob} with the SECOND (dcre_man)
 * read-only datasource wired ({@code dcre.ctv.mandate-source=projection}). The
 * verdict for each spine row is decided by looking up its {@code tx_entry.mandate_ref}
 * in the MSR-owned {@code man_ctv_view}: only an {@code ACCP} projection is
 * collectable, a non-ACCP state and an unknown mandate_ref reject with
 * {@code FAIL_MANDATE_NOT_ACTIVE}, and a NULL mandate_ref is a mandate-gate no-op
 * (the account tier still stands). SCRUM-107: there is no longer a legacy dcre_col
 * table or a head-to-head to run against it; CtvManifestParityTest now pins the
 * account and duplicate tiers only.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange", "dcre.ctv.mandate-source=projection"})
class CtvProjectionGateIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    static String manUrl() {
        return "jdbc:postgresql://" + CRDB.getHost() + ":" + CRDB.getMappedPort(26257)
                + "/dcre_man?sslmode=disable";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.ctv.mandates-db-url", CtvProjectionGateIT::manUrl);
        registry.add("dcre.ctv.mandates-db-user", CRDB::getUsername);
        registry.add("dcre.ctv.mandates-db-password", CRDB::getPassword);
    }

    @BeforeAll
    static void seedProjectionStore() {
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword()));
        root.execute("CREATE DATABASE IF NOT EXISTS dcre_man");

        JdbcTemplate manJdbc = new JdbcTemplate(new DriverManagerDataSource(
                manUrl(), CRDB.getUsername(), CRDB.getPassword()));
        // start_date/expiry_date are VARCHAR(8) CCYYMMDD, matching the real contract:
        // the spine declares both VARCHAR(8) (mrr 001-man-spine) and every dcre_man view
        // down to man_ctv_view carries them through as strings (mrg 004/005/007). This
        // fixture declared DATE until 2026-07-27, which let the whole job-level gate run
        // green while rs.getDate() was crashing against the live projection. The blank
        // expiry is MRR's "no expiry"; the NULL pair is the live cluster's own row shape.
        manJdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL, contract_ref VARCHAR(35) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL, state VARCHAR(16) NOT NULL,
                    start_date VARCHAR(8) NULL, expiry_date VARCHAR(8) NULL,
                    max_collection_amount DECIMAL(18,2) NULL)""");
        manJdbc.execute("""
                CREATE VIEW man_ctv_view AS
                    SELECT mandate_ref, contract_ref, creditor_account, state, start_date,
                           expiry_date, max_collection_amount FROM mandate""");
        manJdbc.update("""
                INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state,
                    start_date, expiry_date, max_collection_amount)
                VALUES ('MND-ACCP','CTR-1','63000000000001','ACCP','20260726','',100000.00),
                       ('MND-PDNG','CTR-1','63000000000001','PDNG','20260726',NULL,100000.00)""");
    }

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void projectionGateVerdictsEachRowByItsMandateRef() throws Exception {
        CtvTestTables.create(jdbc);
        // ACTIVE credit-card account with ample limit: only the mandate gate decides.
        CtvTestTables.insertAccount(jdbc, "63000000000001", "FNBCC", "100000.00", "ACTIVE");

        UUID arrival = UUID.randomUUID();
        insertEntry(arrival, 1, "E2E-P-1", "MND-ACCP");    // ACCP projection -> PASS
        insertEntry(arrival, 2, "E2E-P-2", "MND-PDNG");    // PDNG projection -> reject
        insertEntry(arrival, 3, "E2E-P-3", null);          // NULL mandate_ref -> gate no-op -> PASS
        insertEntry(arrival, 4, "E2E-P-4", "MND-UNKNOWN"); // absent projection -> reject
        CtvTestTables.insertHeader(jdbc, arrival, 4, "FNBCC01");

        JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        Map<Integer, String> expected = Map.of(
                1, "PASS",
                2, "FAIL_MANDATE_NOT_ACTIVE",
                3, "PASS",
                4, "FAIL_MANDATE_NOT_ACTIVE");
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> { actual.put(r.getInt(1), r.getString(2)); }, arrival);
        assertEquals(expected, actual, "projection gate verdicts keyed by mandate_ref");
    }

    void insertEntry(UUID arrival, int sequence, String e2e, String mandateRef) {
        jdbc.update("""
                INSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref,
                    mandate_ref, amount)
                VALUES (?,?,?, '63000000000001', 'CTR-1', ?, 100.00)""",
                arrival, sequence, e2e, mandateRef);
    }
}
