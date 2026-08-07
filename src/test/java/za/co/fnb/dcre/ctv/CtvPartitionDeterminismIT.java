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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-41 determinism gate: the same arrival validated sequentially
 * (max-partitions=1) and partitioned (max-partitions=5) must produce
 * identical (sequence, outcome) sets. Two nested Spring contexts differ only
 * in dcre.ctv.max-partitions; both assert the same oracle map, which pins the
 * partitioned run to the sequential semantics.
 */
class CtvPartitionDeterminismIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        ManProjectionFixture.create(CRDB);
        // SCRUM-107 (review I3): the mandate tier is exercised through the PROJECTION
        // now, so the F51 single-snapshot invariant spans TWO stores. Both partition
        // modes read these same rows AS OF one dcre_man snapshot captured at
        // headerCheck, which is the property this suite exists to pin.
        for (String prefix : new String[] {"6307", "6308"}) {
            ManProjectionFixture.seed(CRDB, "MND-" + prefix + "-LIVE", "CT-DET-1",
                    prefix + "0000000001", "ACCP");
            ManProjectionFixture.seed(CRDB, "MND-" + prefix + "-DEAD", "CT-DET-1",
                    prefix + "0000000001", "RJCT");
        }
    }

    /** The verdict oracle for the 10-row fixture, independent of partitioning. */
    static Map<Integer, String> expected() {
        Map<Integer, String> expected = new HashMap<>();
        expected.put(1, "PASS");
        expected.put(2, "PASS");
        expected.put(3, "FAIL_ACCOUNT_NOT_FOUND");
        expected.put(4, "FAIL_DUPLICATE_TX");        // content clash with seq 1, fresh e2e
        expected.put(5, "FAIL_DUPLICATE_E2E");       // e2e clash with seq 1
        expected.put(6, "FAIL_EXCEEDS_RF_BALANCE");
        // SCRUM-107: these two were the LEGACY contract-match and mandate-cap arms.
        // They now carry the PROJECTION gate's two outcomes instead, so the mandate
        // tier is still exercised in both partition modes: seq 7 targets an ACCP row,
        // seq 8 targets an RJCT one.
        expected.put(7, "PASS");
        expected.put(8, "FAIL_MANDATE_NOT_ACTIVE");
        expected.put(9, "PASS");
        expected.put(10, "PASS");
        return expected;
    }

    static Map<Integer, String> seedAndRun(JdbcTemplate jdbc, JobOperator jobOperator, Job ctvJob,
                                           String accountPrefix) throws Exception {
        CtvTestTables.create(jdbc);
        String mainAccount = accountPrefix + "0000000001";
        String lowCapAccount = accountPrefix + "0000000002";
        String unknownAccount = accountPrefix + "9999999999";
        CtvTestTables.insertAccount(jdbc, mainAccount, "FNBRF", "5000.00", "ACTIVE");
        CtvTestTables.insertAccount(jdbc, lowCapAccount, "FNBRF", "100.00", "ACTIVE");

        UUID arrival = UUID.randomUUID();
        entry(jdbc, arrival, 1, "E2E-DET-1", mainAccount, "CT-DET-1", "100.00", "H-1");
        entry(jdbc, arrival, 2, "E2E-DET-2", mainAccount, "CT-DET-1", "150.00", "H-2");
        entry(jdbc, arrival, 3, "E2E-DET-3", unknownAccount, "CT-DET-1", "100.00", "H-3");
        entry(jdbc, arrival, 4, "E2E-DET-4", mainAccount, "CT-DET-1", "100.00", "H-1");
        entry(jdbc, arrival, 5, "E2E-DET-1", mainAccount, "CT-DET-1", "120.00", "H-5");
        entry(jdbc, arrival, 6, "E2E-DET-6", lowCapAccount, "CT-DET-2", "250.00", "H-6");
        mandateEntry(jdbc, arrival, 7, "E2E-DET-7", mainAccount, "100.00", "H-7",
                "MND-" + accountPrefix + "-LIVE");
        mandateEntry(jdbc, arrival, 8, "E2E-DET-8", mainAccount, "2000.00", "H-8",
                "MND-" + accountPrefix + "-DEAD");
        entry(jdbc, arrival, 9, "E2E-DET-9", mainAccount, "CT-DET-1", "300.00", "H-9");
        entry(jdbc, arrival, 10, "E2E-DET-10", mainAccount, "CT-DET-1", "400.00", "H-10");
        CtvTestTables.insertHeader(jdbc, arrival, 10);

        JobExecution run = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        return actual;
    }

    /** An entry that TARGETS a mandate, so the projection gate decides its verdict. */
    static void mandateEntry(JdbcTemplate jdbc, UUID arrival, int seq, String e2e, String account,
                             String amount, String hash, String mandateRef) {
        CtvTestTables.insertEntry(jdbc, arrival, seq, e2e, account, "CT-DET-1", amount, hash, mandateRef);
    }

    static void entry(JdbcTemplate jdbc, UUID arrival, int seq, String e2e, String account,
                      String contract, String amount, String hash) {
        CtvTestTables.insertEntry(jdbc, arrival, seq, e2e, account, contract, amount, hash);
    }

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange", "dcre.ctv.max-partitions=1"})
    class SequentialRun {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", CRDB::getJdbcUrl);
            registry.add("spring.datasource.username", CRDB::getUsername);
            registry.add("spring.datasource.password", CRDB::getPassword);
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
        void verdictsMatchTheOracleSequentially() throws Exception {
            assertEquals(expected(), seedAndRun(jdbc, jobOperator, ctvJob, "6307"));
        }
    }

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange", "dcre.ctv.max-partitions=5"})
    class PartitionedRun {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", CRDB::getJdbcUrl);
            registry.add("spring.datasource.username", CRDB::getUsername);
            registry.add("spring.datasource.password", CRDB::getPassword);
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
        void verdictsMatchTheOraclePartitioned() throws Exception {
            assertEquals(expected(), seedAndRun(jdbc, jobOperator, ctvJob, "6308"));
        }
    }
}
