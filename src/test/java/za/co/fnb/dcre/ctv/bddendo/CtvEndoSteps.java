package za.co.fnb.dcre.ctv.bddendo;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step definitions for ENDO-mode pass-through semantics (A-20 draft, R-20). */
public class CtvEndoSteps {

    @Autowired
    Job ctvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    JobExecution execution;
    int seq;

    @Before
    public void freshArrival() {
        CtvTestTables.create(jdbc);
        arrival = UUID.randomUUID();
        seq = 0;
    }

    @Given("a collections account {string} with product {string}, cap {string} and status {string}")
    public void account(String number, String product, String cap, String status) {
        CtvTestTables.insertAccount(jdbc, number, product, cap, status);
    }

    @When("CTV validates a collection of {string} against account {string}")
    public void validateSingle(String amount, String account) throws Exception {
        seq++;
        CtvTestTables.insertEntry(jdbc, arrival, seq,
                "ENDO-E2E-" + arrival.toString().substring(0, 8) + "-" + seq, account, null, amount);
        CtvTestTables.insertHeader(jdbc, arrival, seq);
        CtvTestTables.materialiseAccountReference(jdbc);
        execution = jobOperator.start(ctvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
    }

    @Then("the record is marked valid with outcome {string}")
    public void recordValid(String outcome) {
        recordOutcome(outcome);
    }

    @Then("the record is rejected with outcome {string}")
    public void recordRejected(String outcome) {
        recordOutcome(outcome);
    }

    @Then("the job verdict is {string}")
    public void jobVerdict(String verdict) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(verdict, execution.getExecutionContext().getString("ctvVerdict"));
    }

    void recordOutcome(String outcome) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(outcome, jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=1",
                String.class, arrival));
    }
}
