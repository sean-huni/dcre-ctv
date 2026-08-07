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
        ManProjectionFixture.create(CRDB);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        // SCRUM-107 (review C1): declare the mandates store against THIS container.
        // The committed default is localhost:26257; inheriting it makes the suite
        // depend on whatever happens to be listening on the build machine.
        registry.add("dcre.ctv.mandates-db-url", () -> ManProjectionFixture.url(CRDB));
        registry.add("dcre.ctv.mandates-db-user", CRDB::getUsername);
        registry.add("dcre.ctv.mandates-db-password", CRDB::getPassword);
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

        assertNoEntryTargetsAMandate(arrival);
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
        // SCRUM-107: dcre_mandates_sample.sql is gone with dcre_col.mandate. The
        // fixture must NOT re-create a table production dropped, or it would hide
        // the drop from every assertion below.
        for (String file : List.of("dcre_accounts_sample.sql")) {
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
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name='mandate'", Integer.class),
                "dcre_col.mandate was dropped in SCRUM-107; nothing may re-create it");
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

    /**
     * The precondition that licenses translating the mandate tier at all (review I5).
     * A V2 book carries no mandate_ref, so the projection gate is a no-op for every
     * row. If this fixture is ever re-cut with real mandate_refs, the translation
     * below stops being valid and this fails rather than quietly turning a genuine
     * FAIL_MANDATE_NOT_ACTIVE into PASS.
     */
    void assertNoEntryTargetsAMandate(UUID arrival) {
        Integer targeting = jdbc.queryForObject(
                "SELECT count(*) FROM tx_entry WHERE arrival_id=? AND mandate_ref IS NOT NULL",
                Integer.class, arrival);
        assertEquals(0, targeting,
                "this suite translates mandate-tier expectations to PASS, which is only"
                        + " correct while no entry targets a mandate; re-cut as V3 and the"
                        + " manifest needs real projection expectations instead");
    }

    private static final List<String> MANDATE_TIER_OUTCOMES = List.of(
            "FAIL_MANDATE_NOT_FOUND", "FAIL_CONTRACT_MISMATCH", "FAIL_MANDATE_NOT_ACTIVE",
            "FAIL_MANDATE_NOT_EFFECTIVE", "FAIL_MANDATE_EXPIRED", "FAIL_EXCEEDS_MANDATE_CAP");

    /**
     * The toolkit manifest stays the oracle for the account and duplicate tiers,
     * which SCRUM-107 did not touch. Its MANDATE-tier expectations are translated,
     * and the translation is DERIVED from the code contract rather than read off a
     * run: this is a V2 book, V2 details carry no mandate_ref, and
     * {@code VerdictChain.projectionMandateVerdict} returns PASS for a null/blank
     * mandate_ref because such a collection does not target a mandate. So every row
     * whose only fault was a mandate-tier fault now reaches PASS, while every
     * account-tier and duplicate-tier expectation is asserted unchanged.
     *
     * <p>Review I5 asked that {@code FAIL_MANDATE_NOT_ACTIVE} never be translated,
     * because unlike the other five it IS still reachable under the projection gate.
     * The concern is right and the guard for it is structural rather than a name
     * exclusion: what licenses translating ANY mandate-tier row here is that no entry
     * in this fixture targets a mandate. {@link #assertNoEntryTargetsAMandate} asserts
     * that precondition against the seeded spine, so if the fixture is ever re-cut as
     * V3 with real mandate_refs, this test fails loudly instead of silently rewriting
     * a genuine projection FAIL to PASS. Excluding the name alone would not have
     * caught that case; it would only have made this fixture's two legacy-origin
     * rows fail.
     */
    Map<Integer, String> manifestExpectations() throws Exception {
        Map<Integer, String> expected = new HashMap<>();
        List<String> rows = Files.readAllLines(Path.of("src/test/resources/dcre_copybook_v2_dc_sample.manifest.csv"));
        String[] cols = rows.get(0).split(",");
        int seqIdx = List.of(cols).indexOf("detail_seq");
        int outIdx = List.of(cols).indexOf("expected_ctv_outcome");
        int translated = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] parts = rows.get(i).split(",");
            String outcome = parts[outIdx];
            if (MANDATE_TIER_OUTCOMES.contains(outcome)) {
                outcome = "PASS";
                translated++;
            }
            expected.put(Integer.parseInt(parts[seqIdx]), outcome);
        }
        // Guard the translation itself: if the fixture is ever re-cut without
        // mandate-tier faults this silently stops testing anything, so require the
        // rows it is built to translate to actually be present.
        assertEquals(9, translated,
                "the V2 oracle should still carry 9 mandate-tier expectations to translate");
        return expected;
    }
}
