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
import za.co.fnb.dcre.ctv.service.AccountReferenceLoadTasklet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code accountReferenceLoadJob} against a real database: what a successful load leaves
 * behind, and what a FAILING one leaves behind, which matters more.
 *
 * <p>Two nested contexts, one container. They differ only in which artifact they are
 * pointed at: the REAL committed one, and a hand-built one whose second row violates the
 * target table's own CHECK constraint. Everything else, including the schema, is identical,
 * so the difference in outcome is a fact about the artifact and not about the harness.
 */
class AccountReferenceLoadIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    /** The committed dev default, resolved from the service directory the tests run in. */
    static final String REAL_ROOT = "../../../../../../infra/dcre-infra/fixtures/reference/account";
    static final String REAL_VERSION = "2026.08.09-001";

    static final String BAD_VERSION = "2026.08.09-999";

    /** An artifact that is entirely VALID to the reader and illegal to the TABLE. */
    static final Path BAD_ROOT = writeConstraintViolatingArtifact();

    static {
        CRDB.start();
    }

    static void registerDatabase(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    // ------------------------------------------------------------------ the happy path

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange"})
    class TheRealCommittedArtifact {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
            registry.add("dcre.ctv.reference.account.root", () -> REAL_ROOT);
            registry.add("dcre.ctv.reference.account.dataset-version", () -> REAL_VERSION);
        }

        @Autowired
        Job accountReferenceLoadJob;

        @Autowired
        JobOperator jobOperator;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void loadingTheRealArtifactMaterialisesTenRowsAndRecordsWhatItConsumed() throws Exception {
            JobExecution run = jobOperator.start(accountReferenceLoadJob,
                    new JobParametersBuilder().addString("run.id", "real-1", true).toJobParameters());

            assertEquals(BatchStatus.COMPLETED, run.getStatus());
            assertEquals(10, run.getExecutionContext().getInt(AccountReferenceLoadTasklet.APPLIED_ROW_COUNT));
            assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM account", Integer.class),
                    "the COLLECTIONS projection, not the artifact's 110 rows");

            Map<String, Object> load = jdbc.queryForMap(
                    "SELECT * FROM account_reference_load ORDER BY created_at DESC LIMIT 1");
            assertEquals(REAL_VERSION, load.get("dataset_version"));
            // CockroachDB's INT is 64-bit, so these come back as Long. Comparing to an int
            // literal fails on TYPE while the value is right, which is a false red.
            assertEquals(1L, number(load, "schema_version"));
            assertEquals(110L, number(load, "row_count"), "the manifest's count: the WHOLE artifact");
            assertEquals(10L, number(load, "applied_row_count"), "what THIS context materialised");
            assertEquals("5831d612cbcce77f17f5f4ee50dd05cfed14bfc6ce72182649f4eccdb64e75a6",
                    load.get("checksum"));
            assertNotNull(load.get("job_execution_id"),
                    "the run that applied it is recorded, so a row here maps to an execution");
            assertEquals(run.getId(), number(load, "job_execution_id"));

            // The rows are the collections shape, filled: the NOT NULL columns that the
            // retired shared store had to leave nullable are populated here.
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM account WHERE account_number='62114052700219584'");
            assertEquals("FNBRF", row.get("product_code"));
            assertEquals("CACC", row.get("acc_type"));
            assertEquals("250205", row.get("branch_code"));
            assertEquals("100000000201", row.get("ucn"));
            assertEquals(Boolean.FALSE, row.get("edr_ind"));
        }

        @Test
        void aSecondRunReplacesRatherThanAccumulates() throws Exception {
            jobOperator.start(accountReferenceLoadJob, new JobParametersBuilder()
                    .addString("run.id", "real-2a", true).toJobParameters());
            jobOperator.start(accountReferenceLoadJob, new JobParametersBuilder()
                    .addString("run.id", "real-2b", true).toJobParameters());
            assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM account", Integer.class),
                    "the load DELETES then inserts; twenty rows would mean uq_account_account_number"
                            + " is the only thing standing between this and duplicates");
        }
    }

    // -------------------------------------------------- red-proof 5: no partial application

    @Nested
    @SpringBootTest(properties = {"spring.batch.job.enabled=false",
            "dcre.exchange-root=build/test-exchange"})
    class AConstraintViolationRollsTheWholeLoadBack {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
            registry.add("dcre.ctv.reference.account.root", BAD_ROOT::toString);
            registry.add("dcre.ctv.reference.account.dataset-version", () -> BAD_VERSION);
        }

        @Autowired
        Job accountReferenceLoadJob;

        @Autowired
        JobOperator jobOperator;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void thePreviousContentsSurviveAndNeitherNewRowIsApplied() throws Exception {
            jdbc.update("DELETE FROM account");
            jdbc.update("DELETE FROM account_reference_load");
            seedKnownPreviousRow();

            JobExecution run = jobOperator.start(accountReferenceLoadJob,
                    new JobParametersBuilder().addString("run.id", "bad-1", true).toJobParameters());

            assertEquals(BatchStatus.FAILED, run.getStatus(),
                    "a row the target table rejects fails the LOAD; there is no per-row skip");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account", Integer.class),
                    "the table keeps its PREVIOUS contents: the DELETE is in the same transaction"
                            + " as the inserts, so a rollback restores it");
            assertEquals("62000000000099", jdbc.queryForObject(
                    "SELECT account_number FROM account", String.class),
                    "and it is the SAME previous row, not a coincidence of counting");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE account_number IN ('62111111111111','62222222222222')",
                    Integer.class),
                    "not one row of the rejected artifact may be present, including the VALID one"
                            + " that precedes the offending row");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM account_reference_load", Integer.class),
                    "and no load record: a record of a load that did not happen is worse than none");
        }

        @Test
        void theValidRowOfThatArtifactIsGenuinelyInsertable() {
            // Control. Without it, "neither new row is present" is equally consistent with
            // a table that rejects everything, and the rollback assertion above would be
            // passing for the wrong reason. The FIRST artifact row is inserted here by
            // hand and must succeed: the load rolled it back because of the row AFTER it.
            jdbc.update("DELETE FROM account");
            jdbc.update("""
                    INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                         branch_code, balance, max_credit_limit, cancel_reason,
                                         country_id, edr_ind, pre_ind, process_status, status_reason,
                                         ucn, client_id)
                    VALUES ('62111111111111','FNBRF','AAUT','APP-1','CACC','250205',75000.00,
                            NULL,NULL,1,false,false,'ACTIVE',NULL,'100000000201',2)""");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE account_number='62111111111111'",
                    Integer.class));
            jdbc.update("DELETE FROM account");
        }

        private void seedKnownPreviousRow() {
            jdbc.update("""
                    INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                         branch_code, balance, max_credit_limit, cancel_reason,
                                         country_id, edr_ind, pre_ind, process_status, status_reason,
                                         ucn, client_id)
                    VALUES ('62000000000099','FNBRF','AAUT','APP-PREV','CACC','250205',1234.00,
                            NULL,NULL,1,false,false,'ACTIVE',NULL,'100000000099',2)""");
        }
    }

    // ------------------------------------------------------------------ fixture plumbing

    static long number(final Map<String, Object> row, final String column) {
        return ((Number) row.get(column)).longValue();
    }

    static String validRow(final String accountNumber) {
        return "COLLECTIONS," + accountNumber + ",FNBRF,AAUT,,APP-1,CACC,250205,75000.00,,,1,"
                + "false,false,ACTIVE,,100000000201,2";
    }

    /**
     * A COLLECTIONS row carrying BOTH a balance and a credit limit. The reader accepts it,
     * because both cells are decimals, and {@code chk_account_product_amount} rejects it.
     * That is the point: the failure has to come from the TABLE, downstream of every
     * artifact-level check, or this suite would be proving the reader rather than the
     * transaction.
     */
    static String constraintViolatingRow(final String accountNumber) {
        return "COLLECTIONS," + accountNumber + ",FNBRF,AAUT,,APP-2,CACC,250205,75000.00,50000.00,,1,"
                + "false,false,ACTIVE,,100000000202,2";
    }

    static Path writeConstraintViolatingArtifact() {
        try {
            Path root = Files.createTempDirectory("ctv-bad-artifact");
            writeArtifact(root, BAD_VERSION,
                    List.of(validRow("62111111111111"), constraintViolatingRow("62222222222222")));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeArtifact(final Path root, final String version, final List<String> rows) {
        try {
            Path directory = Files.createDirectories(root.resolve(version));
            String header = "shape,account_number,product_code,status,account_type_code,app_no,"
                    + "acc_type,branch_code,balance,max_credit_limit,cancel_reason,country_id,"
                    + "edr_ind,pre_ind,process_status,status_reason,ucn,client_id";
            String csv = header + "\n" + String.join("\n", rows) + "\n";
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve("account.csv"), bytes);
            Files.writeString(directory.resolve("manifest.properties"), """
                    dataset.version=%s
                    schema.version=1
                    source.id=fixture:AccountReferenceLoadIT
                    effective.ts=2026-08-09T00:00:00Z
                    publication.ts=2026-08-09T00:00:00Z
                    row.count=%d
                    checksum.sha256=%s
                    """.formatted(version, rows.size(), sha256(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(final byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
