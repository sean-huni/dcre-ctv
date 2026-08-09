package za.co.fnb.dcre.ctv.bdd;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.ctv.CtvTestTables;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step definitions for R-41 per-client acceptance mode. The PARTIAL override
 * for client FNBRF01 is supplied by the CucumberSpringConfig context
 * properties; FNBCC01 falls through to the ALL_OR_NOTHING default.
 */
public class AcceptanceModeSteps {

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    JobExecution execution;
    String suffix;
    int dupSequence;

    @Before
    public void fresh() {
        CtvTestTables.create(jdbc);
        arrival = UUID.randomUUID();
        suffix = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        dupSequence = 0;
    }

    @Given("client {string} has acceptance mode {word}")
    public void clientMode(String clientToken, String mode) {
        // Documentation step: the mapping is context configuration, asserted
        // end to end through the exit status below.
    }

    @Given("an arrival for {string} whose spine contains a content-duplicate pair")
    public void arrivalWithContentDupPair(String clientToken) {
        String account = "6305" + suffix;
        String contract = "CT-AM-" + suffix.substring(0, 6);
        CtvTestTables.insertAccount(jdbc, account, "FNBRF", "5000.00", "ACTIVE");
        String hash = "HASH-DUP-" + suffix;
        CtvTestTables.insertEntry(jdbc, arrival, 1, e2e(1), account, contract, "100.00", hash);
        // same essential content, fresh e2e: the R-41 content clash
        CtvTestTables.insertEntry(jdbc, arrival, 2, e2e(2), account, contract, "100.00", hash);
        CtvTestTables.insertEntry(jdbc, arrival, 3, e2e(3), account, contract, "200.00", "HASH-OTHER-" + suffix);
        CtvTestTables.insertHeader(jdbc, arrival, 3, clientToken);
        dupSequence = 2;
    }

    @Given("an arrival for {string} whose spine is clean")
    public void cleanArrival(String clientToken) {
        String account = "6306" + suffix;
        String contract = "CT-AM-" + suffix.substring(0, 6);
        CtvTestTables.insertAccount(jdbc, account, "FNBRF", "5000.00", "ACTIVE");
        CtvTestTables.insertEntry(jdbc, arrival, 1, e2e(1), account, contract, "100.00", "HASH-A-" + suffix);
        CtvTestTables.insertEntry(jdbc, arrival, 2, e2e(2), account, contract, "200.00", "HASH-B-" + suffix);
        CtvTestTables.insertHeader(jdbc, arrival, 2, clientToken);
    }

    @When("the CTV job runs for the arrival")
    public void runJob() throws Exception {
        CtvTestTables.materialiseAccountReference(jdbc);
        execution = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
    }

    @Then("the CTV job exit status is {string}")
    public void exitStatusIs(String expected) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(expected, execution.getExitStatus().getExitCode());
    }

    @Then("the duplicate row has verdict FAIL_DUPLICATE_TX")
    public void duplicateRowVerdict() {
        assertEquals("FAIL_DUPLICATE_TX", jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, dupSequence));
    }

    String e2e(int seq) {
        return "E2E-AM-" + arrival.toString().substring(0, 8) + "-" + seq;
    }
}
