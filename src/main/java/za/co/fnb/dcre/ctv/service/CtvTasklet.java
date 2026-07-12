package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;
import za.co.fnb.dcre.ctv.service.VerdictChain.Entry;
import za.co.fnb.dcre.ctv.service.VerdictChain.Mandate;
import za.co.fnb.dcre.platform.model.CtvOutcome;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Set-based verdict pass over one arrival's spine (single as-of snapshot per
 * R-19/Fugu F51): tier-1 defense-in-depth count check (file-fatal), then the
 * item-tier chain in sequence order (deterministic first-wins dup detection,
 * Fugu F50). Upserts keyed (arrival_id, sequence): reruns rewrite identically
 * (R-05). The verdict evaluation date derives from tx_header.business_date so
 * fixture windows are deterministic (toolkit temporal-triple convention).
 */
@Component
public class CtvTasklet implements Tasklet {

    public static final String EXIT_FILE_FATAL = "FILE_FATAL";

    private final JdbcTemplate jdbc;
    private final boolean dcFlow;

    public CtvTasklet(JdbcTemplate jdbc, @Value("${dcre.flow-dc:true}") boolean dcFlow) {
        this.jdbc = jdbc;
        this.dcFlow = dcFlow;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));

        Map<String, Object> header = jdbc.queryForMap(
                "SELECT tx_count, business_date FROM tx_header WHERE arrival_id=?", arrivalId);
        int declared = ((Number) header.get("tx_count")).intValue();
        LocalDate today = LocalDate.parse(((String) header.get("business_date")).strip(),
                java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        List<Entry> entries = jdbc.query(
                "SELECT sequence, e2e, creditor_account, contract_ref, amount FROM tx_entry "
                        + "WHERE arrival_id=? ORDER BY sequence",
                (r, i) -> new Entry(r.getInt(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getBigDecimal(5)), arrivalId);
        if (entries.size() != declared) {
            chunkContext.getStepContext().getStepExecution().getJobExecution()
                    .getExecutionContext().putString("fileFatalReason",
                            "spine count " + entries.size() + " != declared " + declared);
            contribution.setExitStatus(new ExitStatus(EXIT_FILE_FATAL));
            return RepeatStatus.FINISHED;
        }

        // Single as-of snapshot of the reference data (fixture scale).
        Map<String, Account> accounts = new HashMap<>();
        jdbc.query("SELECT account_number, product_code, balance, max_credit_limit, process_status FROM account",
                r -> {
                    accounts.put(r.getString(1), new Account(r.getString(1), r.getString(2),
                            r.getBigDecimal(3), r.getBigDecimal(4), r.getString(5)));
                });
        Map<String, List<Mandate>> mandates = new HashMap<>();
        jdbc.query("SELECT creditor_account, contract_ref, status, start_date, expiry_date, max_collection_amount FROM mandate ORDER BY mandate_ref",
                r -> {
                    mandates.computeIfAbsent(r.getString(1), k -> new java.util.ArrayList<>())
                            .add(new Mandate(r.getString(2), r.getString(3),
                                    r.getDate(4).toLocalDate(),
                                    r.getDate(5) != null ? r.getDate(5).toLocalDate() : null,
                                    r.getBigDecimal(6)));
                });

        Set<String> seen = new HashSet<>();
        boolean anyFail = false;
        for (Entry entry : entries) {
            CtvOutcome outcome = VerdictChain.classify(entry, accounts, mandates, seen, today, dcFlow);
            anyFail |= outcome != CtvOutcome.PASS;
            jdbc.update("UPSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                    arrivalId, entry.sequence(), outcome.name());
        }
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString("ctvVerdict",
                        anyFail ? "BUSINESS_PARTIAL" : "BUSINESS_ACCEPTED");
        return RepeatStatus.FINISHED;
    }
}
