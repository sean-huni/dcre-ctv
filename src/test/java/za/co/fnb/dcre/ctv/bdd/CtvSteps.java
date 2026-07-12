package za.co.fnb.dcre.ctv.bdd;

import io.cucumber.datatable.DataTable;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for the DC-flow verdict chain (R-19 precedence). */
public class CtvSteps {

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

    @Given("account {string} holds an ACTIVE mandate for contract {string} with maximum {string}")
    public void activeMandate(String account, String contract, String maximum) {
        CtvTestTables.insertMandate(jdbc, account, contract, "ACTIVE", "2026-01-01", "none", maximum);
    }

    @Given("account {string} holds these mandates:")
    public void mandates(String account, DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            CtvTestTables.insertMandate(jdbc, account,
                    row.get("contract"), row.get("status"),
                    row.get("start"), row.get("expiry"), row.get("maximum"));
        }
    }

    @When("CTV validates a collection of {string} against account {string} under contract {string}")
    public void validateSingle(String amount, String account, String contract) throws Exception {
        addEntry(account, contract, amount, null);
        CtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("CTV validates a collection of {string} against account {string}")
    public void validateSingleNoContract(String amount, String account) throws Exception {
        addEntry(account, null, amount, null);
        CtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("CTV validates these collections as one arrival:")
    public void validateArrival(DataTable table) throws Exception {
        for (Map<String, String> row : table.asMaps()) {
            addEntry(row.get("account"), row.get("contract"), row.get("amount"), row.get("e2e"));
        }
        CtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("CTV validates the arrival again")
    public void validateAgain() throws Exception {
        run("2");
    }

    @When("CTV validates an arrival whose header declares {int} transactions but whose spine carries {int}")
    public void validateCountMismatch(int declared, int carried) throws Exception {
        for (int i = 0; i < carried; i++) {
            addEntry("63999999999901", null, "10.00", null);
        }
        CtvTestTables.insertHeader(jdbc, arrival, declared);
        run(null);
    }

    @Then("the record is marked valid with outcome {string}")
    public void recordValid(String outcome) {
        recordMarked(1, outcome);
    }

    @Then("the record is rejected with outcome {string}")
    public void recordRejected(String outcome) {
        recordMarked(1, outcome);
    }

    @Then("record {int} is marked {string}")
    public void recordMarked(int sequence, String outcome) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(outcome, jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, sequence));
    }

    @Then("the job verdict is {string}")
    public void jobVerdict(String verdict) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(verdict, execution.getExecutionContext().getString("ctvVerdict"));
    }

    @Then("the arrival is rejected file-fatally with a reason containing {string}")
    public void fileFatal(String reasonPart) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        String reason = execution.getExecutionContext().getString("fileFatalReason");
        assertTrue(reason.contains(reasonPart),
                "expected file-fatal reason containing '" + reasonPart + "' but was: " + reason);
    }

    @Then("the validation log holds exactly {int} verdicts for the arrival")
    public void verdictCount(int expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT count(*) FROM validation_log WHERE arrival_id=?", Integer.class, arrival));
    }

    void addEntry(String account, String contract, String amount, String e2e) {
        seq++;
        String endToEnd = e2e == null || e2e.isBlank()
                ? "E2E-" + arrival.toString().substring(0, 8) + "-" + seq : e2e;
        CtvTestTables.insertEntry(jdbc, arrival, seq, endToEnd, account, contract, amount);
    }

    void run(String attempt) throws Exception {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            builder.addString("attempt", attempt, true);
        }
        execution = jobOperator.start(ctvJob, builder.toJobParameters());
    }
}
