package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.Test;
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
import za.co.fnb.dcre.platform.files.Layouts;
import za.co.fnb.dcre.platform.model.MoneyText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE M2 acceptance test: every validation_log outcome must equal the fixture
 * manifest's expected_ctv_outcome (the Python verifier oracle's expectations),
 * sequence by sequence, for the 30-record DC sample.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class CtvManifestParityTest {

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
    void verdictsMatchTheToolkitManifest() throws Exception {
        UUID arrival = UUID.randomUUID();
        seedReferenceData();
        seedSpine(arrival);

        JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_PARTIAL", run.getExecutionContext().getString("ctvVerdict"),
                "the DC sample carries injected faults");

        Map<Integer, String> expected = manifestExpectations();
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        assertEquals(expected.size(), actual.size(), "one verdict per manifest record");
        for (var e : expected.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey()),
                    "sequence " + e.getKey() + " diverges from the Python oracle");
        }
    }

    void seedReferenceData() throws Exception {
        for (String file : List.of("dcre_accounts_sample.sql", "dcre_mandates_sample.sql")) {
            String sql = Files.readString(Path.of("src/test/resources", file));
            for (String statement : sql.split(";\\s*\\n")) {
                String s = statement.lines()
                        .filter(l -> !l.strip().startsWith("--"))
                        .reduce("", (a, b) -> a + b + "\n").strip();
                if (!s.isEmpty()) {
                    jdbc.execute(s);
                }
            }
        }
        assertEquals(true, jdbc.queryForObject("SELECT count(*)>0 FROM account", Boolean.class));
        assertEquals(true, jdbc.queryForObject("SELECT count(*)>0 FROM mandate", Boolean.class));
    }

    void seedSpine(UUID arrival) throws Exception {
        // CRR owns the spine in production (R-04); the test stands it up here.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE, msg_id_raw VARCHAR(35) NOT NULL,
                    msg_id VARCHAR(35) NOT NULL, created_ts VARCHAR(14) NOT NULL,
                    tx_count INT NOT NULL, initg_pty VARCHAR(35) NOT NULL,
                    business_date VARCHAR(8) NOT NULL, client_token VARCHAR(16),
                    layout_version INT NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL, sequence INT NOT NULL,
                    record_type VARCHAR(2) NOT NULL, e2e_raw VARCHAR(35) NOT NULL,
                    e2e VARCHAR(35) NOT NULL, creditor_account VARCHAR(23) NOT NULL,
                    contract_ref VARCHAR(14), mandate_ref VARCHAR(35), currency VARCHAR(3) NOT NULL,
                    amount_raw VARCHAR(15) NOT NULL, amount DECIMAL(18,2) NOT NULL,
                    branch_code VARCHAR(11), debtor_name VARCHAR(35),
                    debtor_account VARCHAR(23), acc_type_seq VARCHAR(8),
                    content_hash CHAR(64),
                    UNIQUE (arrival_id, sequence))""");
        List<String> lines = Files.readAllLines(Path.of("src/test/resources/dcre_copybook_v2_dc_sample.txt"));
        String header = lines.get(0);
        jdbc.update("""
                UPSERT INTO tx_header (arrival_id, msg_id_raw, msg_id, created_ts, tx_count,
                    initg_pty, business_date, client_token, layout_version)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                arrival, header.substring(4, 26), header.substring(4, 26).strip(),
                Layouts.HEADER.slice(header, "created_ts"),
                Integer.parseInt(Layouts.HEADER.slice(header, "tx_count").strip()),
                Layouts.HEADER.slice(header, "destination_id").strip(),
                Layouts.HEADER.slice(header, "business_date"), "FNBRF01", 2);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            jdbc.update("""
                    UPSERT INTO tx_entry (arrival_id, sequence, record_type, e2e_raw, e2e,
                        creditor_account, contract_ref, currency, amount_raw, amount,
                        branch_code, debtor_name, debtor_account, acc_type_seq)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    arrival, i,
                    Layouts.DETAIL_V2.slice(line, "record_type"),
                    Layouts.DETAIL_V2.slice(line, "end_to_end"),
                    Layouts.DETAIL_V2.slice(line, "end_to_end").stripTrailing(),
                    Layouts.DETAIL_V2.slice(line, "creditor_account").strip(),
                    Layouts.DETAIL_V2.slice(line, "contract_ref").strip(),
                    Layouts.DETAIL_V2.slice(line, "currency"),
                    Layouts.DETAIL_V2.slice(line, "amount"),
                    MoneyText.parse(Layouts.DETAIL_V2.slice(line, "amount"), 2),
                    Layouts.DETAIL_V2.slice(line, "branch_code").strip(),
                    Layouts.DETAIL_V2.slice(line, "debtor_name").strip(),
                    Layouts.DETAIL_V2.slice(line, "debtor_account").strip(),
                    Layouts.DETAIL_V2.slice(line, "acc_type_seq"));
        }
    }

    Map<Integer, String> manifestExpectations() throws Exception {
        Map<Integer, String> expected = new HashMap<>();
        List<String> rows = Files.readAllLines(Path.of("src/test/resources/dcre_copybook_v2_dc_sample.manifest.csv"));
        String[] cols = rows.get(0).split(",");
        int seqIdx = List.of(cols).indexOf("detail_seq");
        int outIdx = List.of(cols).indexOf("expected_ctv_outcome");
        for (int i = 1; i < rows.size(); i++) {
            String[] parts = rows.get(i).split(",");
            expected.put(Integer.parseInt(parts[seqIdx]), parts[outIdx]);
        }
        return expected;
    }
}
