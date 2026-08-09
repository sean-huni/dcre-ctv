package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.Nested;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three states of the account reference, RUN against a real database and required to
 * produce three DIFFERENT outcomes. They are one test class because their whole value is
 * the contrast: each is only meaningful next to the other two.
 *
 * <ol>
 *   <li>{@link NothingEverLoaded}: the table is empty because no loader has ever run.
 *       TECHNICAL. The job FAILS naming the materialisation step, and writes ZERO business
 *       verdicts.</li>
 *   <li>{@link LoadedAndTheAccountIsGenuinelyAbsent}: the table is loaded and this account
 *       is not in it. BUSINESS. The job SUCCEEDS and writes
 *       {@code FAIL_ACCOUNT_NOT_FOUND}.</li>
 *   <li>{@link LoadedButAppliedNothing}: a load ran and applied nothing. TECHNICAL, with
 *       its own message, because the remedy differs: the step ran, so it is the artifact
 *       that needs looking at.</li>
 * </ol>
 *
 * <p>The artifact-absent state is the LOADER's, not the validation job's, and is asserted
 * at the job level in {@code AccountReferenceLoadIT} and at the reader level in
 * {@code AccountArtifactReaderTest}.
 *
 * <p><b>Why the emptiness is asserted before every run.</b> An "empty table" test that
 * passes because the artifact happened to load proves nothing at all: it would be green
 * with the guard deleted. Each context below therefore reads the two tables FIRST and
 * fails if they are not in the state the test is named after, so a fixture that silently
 * stops producing that state is a failure rather than a false pass.
 */
class AccountReferenceGuardIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        // The mandates store must exist before any context resolves its URL: the mandate
        // gate opens that connection on every DC arrival, and a suite that does not declare
        // it silently borrows localhost:26257 from whatever else is running on the machine.
        ManProjectionFixture.create(CRDB);
    }

    static void registerDatabase(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.ctv.mandates-db-url", () -> ManProjectionFixture.url(CRDB));
        registry.add("dcre.ctv.mandates-db-user", CRDB::getUsername);
        registry.add("dcre.ctv.mandates-db-password", CRDB::getPassword);
    }

    /**
     * The three contexts below share ONE database, so each must establish its own reference
     * state rather than inherit the previous one's. This is not tidiness: the FIRST run of
     * this suite had {@code NothingEverLoaded} reading two accounts left behind by a sibling
     * context, and only the positive control in that test turned it into a failure instead
     * of a silent pass against the wrong state.
     */
    static void clearReferenceState(final JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM validation_log");
        jdbc.update("DELETE FROM account_reference_load");
        jdbc.update("DELETE FROM account");
    }

    /** The account the arrivals below collect against. Never in any artifact or fixture. */
    static final String ABSENT_ACCOUNT = "63970000000404";

    static UUID seedOneEntryArrival(final JdbcTemplate jdbc, final String account) {
        CtvTestTables.create(jdbc);
        clearReferenceState(jdbc);
        UUID arrival = UUID.randomUUID();
        CtvTestTables.insertEntry(jdbc, arrival, 1, "E2E-GUARD-1", account, null, "100.00");
        CtvTestTables.insertHeader(jdbc, arrival, 1, "FNBCC01");
        return arrival;
    }

    static List<String> outcomes(final JdbcTemplate jdbc, final UUID arrival) {
        return jdbc.queryForList("SELECT outcome FROM validation_log WHERE arrival_id=?",
                String.class, arrival);
    }

    static String rootCauseMessage(final JobExecution run) {
        return run.getAllFailureExceptions().stream()
                .map(Throwable::toString)
                .reduce("", (a, b) -> a + " | " + b);
    }

    // ------------------------------------------------------------ 1. TECHNICAL: never loaded

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange"})
    class NothingEverLoaded {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        Job ctvJob;

        @Autowired
        JobOperator jobOperator;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void anUnloadedTableFailsTheJobNamingTheStepAndWritesNoBusinessVerdicts() throws Exception {
            UUID arrival = seedOneEntryArrival(jdbc, ABSENT_ACCOUNT);

            // POSITIVE CONTROL. Without these two the test could pass because some other
            // context in this container had already loaded the artifact, and it would then
            // be asserting nothing whatever about an unloaded database.
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account", Integer.class),
                    "this test is about an EMPTY account table; it proves nothing if rows exist");
            assertEquals(0, jdbc.queryForObject(
                            "SELECT count(*) FROM account_reference_load", Integer.class),
                    "this test is about a table NOTHING has loaded; a load record here voids it");

            JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                    .addString("arrival.id", arrival.toString(), true).toJobParameters());

            assertEquals(BatchStatus.FAILED, run.getStatus(),
                    "a deployment step that never happened is a TECHNICAL failure, not a verdict");

            String failure = rootCauseMessage(run);
            assertTrue(failure.contains("AccountReferenceNotMaterialisedException"),
                    "the failure must be the guard's own type, not any other way a job can die,"
                            + " was: " + failure);
            assertTrue(failure.contains("NEVER been materialised"),
                    "the message must say the data was never loaded, was: " + failure);
            assertTrue(failure.contains("materialise-account-reference.sh"),
                    "the message must NAME the materialisation step an operator has to run,"
                            + " was: " + failure);
            assertTrue(failure.contains("DCRE_CTV_JOB_NAME=accountReferenceLoadJob"),
                    "the message must name the loader job, was: " + failure);

            // THE POINT OF THE WHOLE CHANGE. Before the guard this arrival produced one
            // FAIL_ACCOUNT_NOT_FOUND per row: loud per transaction, and completely
            // misleading in aggregate, because it reads as a data-quality problem in the
            // input file when the input file is fine.
            assertFalse(outcomes(jdbc, arrival).contains("FAIL_ACCOUNT_NOT_FOUND"),
                    "an unloaded reference table must NEVER be reported as a business rejection");
            assertEquals(List.of(), outcomes(jdbc, arrival),
                    "no verdict at all may be formed against a table nothing has loaded");
        }
    }

    // ------------------------------------------------------- 2. BUSINESS: loaded, no match

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange"})
    class LoadedAndTheAccountIsGenuinelyAbsent {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        Job ctvJob;

        @Autowired
        JobOperator jobOperator;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void aLoadedTableWithoutThisAccountIsABusinessRejectionAndTheJobSucceeds() throws Exception {
            UUID arrival = seedOneEntryArrival(jdbc, ABSENT_ACCOUNT);
            // A DIFFERENT account, so the table is genuinely loaded and genuinely does not
            // hold the one the arrival collects against. That is the distinction under test:
            // a fixture where the table were empty would be state 1 wearing state 2's name.
            CtvTestTables.insertAccount(jdbc, "63000000000777", "FNBCC", "100000.00", "ACTIVE");
            CtvTestTables.materialiseAccountReference(jdbc);

            assertTrue(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class) > 0,
                    "this test is about a LOADED table; an empty one makes it state 1");

            JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                    .addString("arrival.id", arrival.toString(), true).toJobParameters());

            assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                    "an account the artifact does not carry is a finding about the collection,"
                            + " so the job completes and reports it");
            assertEquals(List.of("FAIL_ACCOUNT_NOT_FOUND"), outcomes(jdbc, arrival));
        }

        @Test
        void theRunRecordsWhichDatasetVersionItWasJudgedAgainst() throws Exception {
            UUID arrival = seedOneEntryArrival(jdbc, "63000000000888");
            CtvTestTables.insertAccount(jdbc, "63000000000888", "FNBCC", "100000.00", "ACTIVE");
            CtvTestTables.materialiseAccountReference(jdbc);

            JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                    .addString("arrival.id", arrival.toString(), true).toJobParameters());

            assertEquals(BatchStatus.COMPLETED, run.getStatus());
            assertEquals("2026.08.09-001",
                    run.getExecutionContext().getString("accountDatasetVersion"),
                    "which reference data this run used must be answerable from Batch metadata"
                            + " afterwards, not inferred from whatever the table holds later");
        }
    }

    // ------------------------------------------------- 3. TECHNICAL: loaded, applied nothing

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange"})
    class LoadedButAppliedNothing {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        Job ctvJob;

        @Autowired
        JobOperator jobOperator;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void aLoadThatAppliedZeroRowsIsTechnicalAndCarriesItsOwnMessage() throws Exception {
            UUID arrival = seedOneEntryArrival(jdbc, ABSENT_ACCOUNT);
            // Written by hand rather than through materialiseAccountReference, which refuses
            // this state on purpose: here it IS the state under test.
            jdbc.update("""
                    INSERT INTO account_reference_load (dataset_version, schema_version, source_id,
                        effective_ts, publication_ts, row_count, checksum, applied_row_count)
                    VALUES ('2026.08.09-002', 1, 'fixture:empty-projection', '2026-08-09T00:00:00Z',
                            '2026-08-09T00:00:00Z', 110, 'fixture', 0)""");

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account", Integer.class),
                    "this test is about a load that applied nothing; seeded rows would void it");

            JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                    .addString("arrival.id", arrival.toString(), true).toJobParameters());

            assertEquals(BatchStatus.FAILED, run.getStatus());
            String failure = rootCauseMessage(run);
            assertTrue(failure.contains("applied ZERO rows"),
                    "a load that applied nothing must not be reported as 'never materialised':"
                            + " the step DID run, so the artifact is what needs looking at, was: "
                            + failure);
            assertTrue(failure.contains("2026.08.09-002"),
                    "the message must name the version that applied nothing, was: " + failure);
            assertEquals(List.of(), outcomes(jdbc, arrival),
                    "still no business verdicts: the table is empty for a non-business reason");
        }
    }
}
