package za.co.fnb.dcre.ctv;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import za.co.fnb.dcre.ctv.service.ValidationService;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ENDO-mode (dcre.flow-dc=false) verdict semantics, SCRUM-32 / A-20 draft.
 *
 * [SYNTHETIC-CONTRACT R-35] The pass-through semantics asserted here are
 * invented for the A-20 draft: on ENDO, AIS creates absent accounts
 * downstream (create-if-absent), so an unknown account and a known account
 * with a NULL cap (freshly-creatable, cap check post-init) both PASS.
 * Existing over-cap accounts keep their DC failure outcome, and the mandate
 * layer stays off (R-20). DC-flow behavior is untouched; the manifest parity
 * test remains the DC oracle.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange", "dcre.flow-dc=false"})
class CtvEndoModeTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void endoPassesThroughUnknownAndNullCapAccountsButKeepsOverCapFailing() throws Exception {
        UUID arrival = UUID.randomUUID();
        seedReferenceData();
        seedSpine(arrival);

        Logger validationLogger = (Logger) LoggerFactory.getLogger(ValidationService.class);
        ListAppender<ILoggingEvent> warns = new ListAppender<>();
        warns.start();
        validationLogger.addAppender(warns);

        JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_PARTIAL", run.getExecutionContext().getString("ctvVerdict"),
                "the over-cap entry must still fail on ENDO");

        Map<Integer, String> expected = Map.of(
                1, "PASS",                    // unknown account: AIS creates it downstream
                2, "PASS",                    // second unknown account, same pass-through
                3, "PASS",                    // known, under cap
                4, "PASS",                    // known, NULL cap: cap check post-init
                5, "FAIL_EXCEEDS_RF_BALANCE"  // existing over-cap keeps its DC outcome
        );
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        assertEquals(expected, actual, "ENDO verdicts per A-20 draft [SYNTHETIC-CONTRACT R-35]");

        // R-38 exclusion visibility: exactly one FAIL verdict -> exactly one WARN at decision time.
        List<String> exclusionWarns = warns.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("excluded stage=CTV"))
                .toList();
        assertEquals(1, exclusionWarns.size(), "one WARN per FAIL verdict (R-38)");
        assertEquals("excluded stage=CTV arrival=" + arrival + " seq=5 e2e=ENDO-E2E-00005"
                + " reason=CTV_FAIL_EXCEEDS_RF_BALANCE", exclusionWarns.get(0),
                "uniform R-38 WARN shape");
        validationLogger.detachAppender(warns);
    }

    void seedReferenceData() {
        // Minimal AIS-shaped read models (CTV maps only these columns).
        // [SYNTHETIC-CONTRACT R-35] Deliberately WITHOUT the fixture seed's
        // product/cap CHECK constraint: the ENDO NULL-cap row models exactly
        // the pre-init account state that constraint forbids for settled rows.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    account_number VARCHAR(34) NOT NULL UNIQUE,
                    product_code VARCHAR(8) NOT NULL,
                    balance DECIMAL(18,2) NULL,
                    max_credit_limit DECIMAL(18,2) NULL,
                    process_status VARCHAR(16) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    start_date DATE NOT NULL,
                    expiry_date DATE NULL,
                    max_collection_amount DECIMAL(18,2) NOT NULL)""");
        // ENDO carries no bank-registered mandates: the mandate table stays empty.
        upsertAccount("62000000000001", "FNBRF", new BigDecimal("5000.00"), null);   // under cap
        upsertAccount("62000000000002", "FNBCC", null, null);                        // NULL cap
        upsertAccount("62000000000003", "FNBRF", new BigDecimal("100.00"), null);    // over cap target
    }

    void upsertAccount(String number, String productCode, BigDecimal balance, BigDecimal limit) {
        jdbc.update("""
                UPSERT INTO account (account_number, product_code, balance, max_credit_limit, process_status)
                VALUES (?,?,?,?,'ACTIVE')""", number, productCode, balance, limit);
    }

    void seedSpine(UUID arrival) {
        // CRR owns the spine in production (R-04); the test stands it up here,
        // mirroring CtvManifestParityTest but with hand-rolled ENDO entries.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE,
                    tx_count INT NOT NULL,
                    initg_pty VARCHAR(35) NOT NULL,
                    business_date VARCHAR(8) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL, sequence INT NOT NULL,
                    e2e VARCHAR(35) NOT NULL, creditor_account VARCHAR(23) NOT NULL,
                    contract_ref VARCHAR(14), mandate_ref VARCHAR(35), amount DECIMAL(18,2) NOT NULL,
                    content_hash CHAR(64),
                    UNIQUE (arrival_id, sequence))""");
        jdbc.update("UPSERT INTO tx_header (arrival_id, tx_count, initg_pty, business_date) VALUES (?,?,?,?)",
                arrival, 5, "FNBEN01", "20260711");
        insertEntry(arrival, 1, "ENDO-E2E-00001", "62999999999901", "150.00"); // unknown
        insertEntry(arrival, 2, "ENDO-E2E-00002", "62999999999902", "220.00"); // unknown
        insertEntry(arrival, 3, "ENDO-E2E-00003", "62000000000001", "100.00"); // under cap
        insertEntry(arrival, 4, "ENDO-E2E-00004", "62000000000002", "300.00"); // NULL cap
        insertEntry(arrival, 5, "ENDO-E2E-00005", "62000000000003", "250.00"); // over cap
    }

    void insertEntry(UUID arrival, int sequence, String e2e, String account, String amount) {
        jdbc.update("""
                UPSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref, amount)
                VALUES (?,?,?,?,NULL,?)""",
                arrival, sequence, e2e, account, new BigDecimal(amount));
    }
}
