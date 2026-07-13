package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.BeforeEach;
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
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogBatchDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-41 rollup + outcome seam: the ACTUAL seam file content (what AGT reads),
 * not just the in-JVM exit status, is asserted for the three terminal shapes;
 * the zero-tx arrival exercises the empty-partition path; and a content-dup on
 * a failing account proves the phase-1 dup verdict survives phase 2 at the job
 * level (ON CONFLICT DO NOTHING). Default acceptance mode = ALL_OR_NOTHING.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class CtvSeamAndRollupIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    // Absolute, fresh per class: removes relative-cwd ambiguity and stale-file
    // pollution so the assertion reads exactly this run's seam file.
    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("ctv-seam-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ValidationLogBatchDao batchDao;

    @BeforeEach
    void clearOutcomes() throws Exception {
        Path outcomes = EXCHANGE.resolve("outcomes");
        if (Files.isDirectory(outcomes)) {
            try (var files = Files.newDirectoryStream(outcomes)) {
                for (Path f : files) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    private JobExecution run(UUID arrival) throws Exception {
        return jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
    }

    /** Exactly one job ran since clearOutcomes, so read the single seam file it wrote. */
    private String seamContent() throws Exception {
        Path outcomes = EXCHANGE.resolve("outcomes");
        try (var files = Files.list(outcomes)) {
            List<Path> written = files.toList();
            assertEquals(1, written.size(), "expected exactly one seam file, got " + written);
            return Files.readAllLines(written.get(0)).get(0);
        }
    }

    @Test
    void allOrNothingFailWritesBusinessFileRejectedSeamAndKeepsDupVerdict() throws Exception {
        CtvTestTables.create(jdbc);
        UUID arrival = UUID.randomUUID();
        // Content-dup pair on an UNKNOWN account: phase 2 would classify
        // FAIL_ACCOUNT_NOT_FOUND, but the phase-1 FAIL_DUPLICATE_TX must win.
        String unknown = "63979999999901";
        CtvTestTables.insertEntry(jdbc, arrival, 1, "E2E-SR-1", unknown, null, "100.00", "HASH-SR");
        CtvTestTables.insertEntry(jdbc, arrival, 2, "E2E-SR-2", unknown, null, "100.00", "HASH-SR");
        CtvTestTables.insertHeader(jdbc, arrival, 2, "FNBCC01"); // FNBCC01 -> default ALL_OR_NOTHING

        JobExecution run = run(arrival);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_FILE_REJECTED", run.getExitStatus().getExitCode());
        assertEquals("BUSINESS_FILE_REJECTED", seamContent());
        assertEquals("FAIL_DUPLICATE_TX", jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=2",
                String.class, arrival));
    }

    @Test
    void fileFatalWritesBusinessFileFatalSeam() throws Exception {
        CtvTestTables.create(jdbc);
        UUID arrival = UUID.randomUUID();
        CtvTestTables.insertEntry(jdbc, arrival, 1, "E2E-FF-1", "63970000000001", null, "100.00", "HASH-FF");
        CtvTestTables.insertHeader(jdbc, arrival, 3, "FNBCC01"); // declares 3, carries 1

        JobExecution run = run(arrival);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_FILE_FATAL", seamContent());
        assertEquals("spine count 1 != declared 3",
                run.getExecutionContext().getString("fileFatalReason"));
    }

    @Test
    void zeroTxArrivalReachesRollupAndIsAccepted() throws Exception {
        CtvTestTables.create(jdbc);
        UUID arrival = UUID.randomUUID();
        CtvTestTables.insertHeader(jdbc, arrival, 0, "FNBCC01"); // no spine entries

        JobExecution run = run(arrival);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_ACCEPTED", run.getExitStatus().getExitCode());
        assertEquals("BUSINESS_ACCEPTED", seamContent());
        assertEquals(List.of(), jdbc.queryForList(
                "SELECT outcome FROM validation_log WHERE arrival_id=?", String.class, arrival));
    }

    /**
     * R-41 phase-1-dup-wins at the DAO (ON CONFLICT DO NOTHING), bypassing the
     * in-memory skip filter: a phase-2 batch write must never overwrite a dup
     * verdict the sequential scan already wrote for the same (arrival, sequence).
     */
    @Test
    void batchUpsertNeverOverwritesAPhaseOneDupVerdict() {
        UUID arrival = UUID.randomUUID();
        jdbc.update("INSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?, 'FAIL_DUPLICATE_TX')",
                arrival, 7);

        batchDao.upsertAll(List.of(ValidationLogEntity.of(arrival, 7, "FAIL_ACCOUNT_NOT_FOUND")));

        assertEquals("FAIL_DUPLICATE_TX", jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, 7), "phase-1 dup verdict must survive phase-2 write");
    }
}
