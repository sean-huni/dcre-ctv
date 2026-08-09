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
 * <p><b>SCRUM-107 INVERTED two assertions in this suite, and they were asserting the bug.</b>
 * An unknown account used to be expected to PASS on ENDO, on the A-20 draft reasoning that
 * it would be created downstream (create-if-absent). That left the first tier of the chain
 * answering PASS in exactly the case it exists to catch: a fail-closed control that does
 * not fail closed, silently, with no suite red and nothing in the log. The identical defect
 * was found in {@code payments/ptv}, whose chain is a fork of this one, and both are
 * repaired the same way. Sequences 1 and 2 below therefore expect
 * {@code FAIL_ACCOUNT_NOT_FOUND} where they expected PASS, and the WARN assertion counts
 * three exclusions where it counted one.
 *
 * <p>[SYNTHETIC-CONTRACT R-35] What survives the repair: an EXISTING account whose cap is
 * unset still passes on ENDO, because that row is present and only its limit is unknown.
 * That arm is asserted in {@code VerdictChainAccountTierTest} rather than here, since
 * {@code chk_account_product_amount} on {@code dcre_col.account} makes such a row
 * unreachable through the table. Existing over-cap accounts keep their DC failure outcome,
 * and the mandate layer stays off (R-20). The manifest parity test remains the DC oracle.
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
        // SCRUM-107 (review C1b): deliberately a CLOSED port. ENDO has no mandate
        // gate (R-20), so nothing here may open a dcre_man connection. If the ENDO
        // path ever contacts the projection again, these suites go red here rather
        // than taking collections-ENDO down whenever mandates is unreachable.
        registry.add("dcre.ctv.mandates-db-url", () -> ManProjectionFixture.CLOSED_PORT_URL);
    }

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void endoRejectsUnknownAccountsAndKeepsOverCapFailing() throws Exception {
        UUID arrival = UUID.randomUUID();
        seedReferenceData();
        seedSpine(arrival);

        Logger validationLogger = (Logger) LoggerFactory.getLogger(ValidationService.class);
        ListAppender<ILoggingEvent> warns = new ListAppender<>();
        warns.start();
        validationLogger.addAppender(warns);

        CtvTestTables.materialiseAccountReference(jdbc);
        JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_PARTIAL", run.getExecutionContext().getString("ctvVerdict"),
                "the over-cap entry must still fail on ENDO");

        Map<Integer, String> expected = Map.of(
                1, "FAIL_ACCOUNT_NOT_FOUND",  // INVERTED by SCRUM-107: was PASS, and was the bug
                2, "FAIL_ACCOUNT_NOT_FOUND",  // INVERTED by SCRUM-107: second unknown account
                3, "PASS",                    // known, under cap
                4, "PASS",                    // known credit card, under its limit
                5, "FAIL_EXCEEDS_RF_BALANCE", // existing over-cap keeps its DC outcome
                6, "PASS"                     // targets a mandate, but ENDO has no gate (R-20)
        );
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        assertEquals(expected, actual, "ENDO verdicts per A-20 draft [SYNTHETIC-CONTRACT R-35]");

        // R-38 exclusion visibility: one WARN per FAIL verdict at decision time. THREE now,
        // not one: the two rejected unknown accounts are exclusions like any other and must
        // be as visible to an operator as the over-cap one.
        List<String> exclusionWarns = warns.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("excluded stage=CTV"))
                .sorted()
                .toList();
        assertEquals(3, exclusionWarns.size(), "one WARN per FAIL verdict (R-38), was: " + exclusionWarns);
        assertEquals(List.of(
                "excluded stage=CTV arrival=" + arrival + " seq=1 e2e=ENDO-E2E-00001"
                        + " reason=CTV_FAIL_ACCOUNT_NOT_FOUND",
                "excluded stage=CTV arrival=" + arrival + " seq=2 e2e=ENDO-E2E-00002"
                        + " reason=CTV_FAIL_ACCOUNT_NOT_FOUND",
                "excluded stage=CTV arrival=" + arrival + " seq=5 e2e=ENDO-E2E-00005"
                        + " reason=CTV_FAIL_EXCEEDS_RF_BALANCE"),
                exclusionWarns, "uniform R-38 WARN shape, one per exclusion");
        validationLogger.detachAppender(warns);
    }

    void seedReferenceData() {
        // SCRUM-107: dcre_col.account is created by CTV's OWN changelog now, in the full
        // collections shape with its NOT NULLs and its three CHECK constraints, and filled
        // in production by accountReferenceLoadJob from the versioned artifact. This suite
        // no longer stands up a five-column stand-in of its own: a CREATE TABLE IF NOT
        // EXISTS would be a silent no-op against the real table while looking like the
        // thing under test.
        // dcre_col.mandate was dropped; ENDO never had a mandate gate.
        CtvTestTables.insertAccount(jdbc, "62000000000001", "FNBRF", "5000.00", "ACTIVE"); // under cap
        CtvTestTables.insertAccount(jdbc, "62000000000002", "FNBCC", "5000.00", "ACTIVE"); // under limit
        CtvTestTables.insertAccount(jdbc, "62000000000003", "FNBRF", "100.00", "ACTIVE");  // over cap
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
                arrival, 6, "FNBEN01", "20260711");
        insertEntry(arrival, 1, "ENDO-E2E-00001", "62999999999901", "150.00"); // unknown
        insertEntry(arrival, 2, "ENDO-E2E-00002", "62999999999902", "220.00"); // unknown
        insertEntry(arrival, 3, "ENDO-E2E-00003", "62000000000001", "100.00"); // under cap
        insertEntry(arrival, 4, "ENDO-E2E-00004", "62000000000002", "300.00"); // NULL cap
        insertEntry(arrival, 5, "ENDO-E2E-00005", "62000000000003", "250.00"); // over cap
        // SCRUM-107 (review NEW-2): an ENDO row that DOES target a mandate. Every other
        // ENDO fixture row carries a NULL mandate_ref, which made this whole suite
        // structurally unable to see the defect: with a NULL ref the projection lookup
        // short-circuits on the empty collection and never validates the as-of string.
        // This row makes the set non-empty, so an unguarded projectionByRef would hand
        // the deliberately empty ENDO snapshot to requireHlc and fail the entire job.
        // Combined with the closed-port mandates URL on this context, it is a live
        // assertion that ENDO neither reads nor needs the projection.
        insertMandateTargetingEntry(arrival, 6, "ENDO-E2E-00006", "62000000000001", "100.00",
                "MND-ENDO-SHOULD-NEVER-BE-LOOKED-UP");
    }

    void insertEntry(UUID arrival, int sequence, String e2e, String account, String amount) {
        jdbc.update("""
                UPSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref, amount)
                VALUES (?,?,?,?,NULL,?)""",
                arrival, sequence, e2e, account, new BigDecimal(amount));
    }

    void insertMandateTargetingEntry(UUID arrival, int sequence, String e2e, String account,
                                     String amount, String mandateRef) {
        jdbc.update("""
                UPSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref,
                                      mandate_ref, amount)
                VALUES (?,?,?,?,NULL,?,?)""",
                arrival, sequence, e2e, account, mandateRef, new BigDecimal(amount));
    }
}
