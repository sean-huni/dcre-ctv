package za.co.fnb.dcre.ctv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.ctv.data.model.TxEntryView;
import za.co.fnb.dcre.ctv.data.model.TxHeaderView;
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;
import za.co.fnb.dcre.ctv.data.repo.AccountRepo;
import za.co.fnb.dcre.ctv.data.repo.MandateRepo;
import za.co.fnb.dcre.ctv.data.repo.TxEntryViewRepo;
import za.co.fnb.dcre.ctv.data.repo.TxHeaderViewRepo;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogBatchDao;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogRepo;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;
import za.co.fnb.dcre.ctv.service.VerdictChain.Entry;
import za.co.fnb.dcre.ctv.service.VerdictChain.Mandate;
import za.co.fnb.dcre.platform.model.CtvOutcome;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business tier (configuration.md point 21): the R-19 two-tier verdict pass
 * over one arrival, split for R-41 partitioning. Tier 1 (header/count check)
 * runs once in the headerCheck step; the dup scan runs set-based once; tier 2
 * runs per sequence range against a range-scoped snapshot (Fugu F51 as-of
 * semantics preserved: reference data is read once per range inside one step
 * transaction). Upserts keyed (arrival_id, sequence): R-05.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    public sealed interface HeaderCheck {
        record FileFatal(String reason) implements HeaderCheck { }
        record Ok(int txCount, String clientToken) implements HeaderCheck { }
    }

    private final TxHeaderViewRepo headers;
    private final TxEntryViewRepo entries;
    private final AccountRepo accounts;
    private final MandateRepo mandates;
    private final ValidationLogRepo verdicts;
    private final ValidationLogBatchDao verdictBatch;
    private final boolean dcFlow;

    public ValidationService(TxHeaderViewRepo headers, TxEntryViewRepo entries, AccountRepo accounts,
                             MandateRepo mandates, ValidationLogRepo verdicts,
                             ValidationLogBatchDao verdictBatch,
                             @Value("${dcre.flow-dc:true}") boolean dcFlow) {
        this.headers = headers;
        this.entries = entries;
        this.accounts = accounts;
        this.mandates = mandates;
        this.verdicts = verdicts;
        this.verdictBatch = verdictBatch;
        this.dcFlow = dcFlow;
    }

    /** Tier 1: header presence + declared-vs-carried count (R-19). */
    public HeaderCheck checkHeader(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        long spineCount = entries.countByArrivalId(arrivalId);
        if (spineCount != header.getTxCount()) {
            return new HeaderCheck.FileFatal("spine count " + spineCount + " != declared " + header.getTxCount());
        }
        String clientToken = header.getInitgPty() == null ? "" : header.getInitgPty().strip();
        return new HeaderCheck.Ok(header.getTxCount(), clientToken);
    }

    /**
     * Tier 2 for one partition range (sequence bounds inclusive). Rows already
     * verdicted by the dup scan (or an earlier run, R-05 replay) are skipped.
     * Returns the number of FAIL verdicts written by this range.
     */
    public int validateRange(UUID arrivalId, int fromSeq, int toSeq) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        LocalDate today = LocalDate.parse(header.getBusinessDate().strip(), DateTimeFormatter.BASIC_ISO_DATE);

        Set<Integer> alreadyVerdicted =
                Set.copyOf(verdicts.sequencesForArrivalInRange(arrivalId, fromSeq, toSeq));
        List<TxEntryView> rows =
                entries.findByArrivalIdAndSequenceBetweenOrderBySequence(arrivalId, fromSeq, toSeq)
                        .stream()
                        .filter(row -> !alreadyVerdicted.contains(row.getSequence()))
                        .toList();
        if (rows.isEmpty()) {
            return 0;
        }

        Set<String> accountNumbers = rows.stream()
                .map(TxEntryView::getCreditorAccount)
                .collect(Collectors.toSet());
        Map<String, Account> accountsByNumber = new HashMap<>();
        accounts.findByAccountNumberIn(accountNumbers).forEach(a -> accountsByNumber.put(a.getAccountNumber(),
                new Account(a.getAccountNumber(), a.getProductCode(), a.getBalance(),
                        a.getMaxCreditLimit(), a.getProcessStatus())));
        Map<String, List<Mandate>> mandatesByAccount = new HashMap<>();
        for (var m : mandates.findByCreditorAccountInOrderByMandateRef(accountNumbers)) {
            mandatesByAccount.computeIfAbsent(m.getCreditorAccount(), k -> new ArrayList<>())
                    .add(new Mandate(m.getContractRef(), m.getStatus(), m.getStartDate(),
                            m.getExpiryDate(), m.getMaxCollectionAmount()));
        }

        List<ValidationLogEntity> batch = new ArrayList<>(rows.size());
        int fails = 0;
        for (TxEntryView row : rows) {
            Entry entry = new Entry(row.getSequence(), row.getE2e(), row.getCreditorAccount(),
                    row.getContractRef(), row.getAmount());
            CtvOutcome outcome = VerdictChain.classify(entry, accountsByNumber, mandatesByAccount,
                    today, dcFlow);
            if (outcome != CtvOutcome.PASS) {
                fails++;
                // R-38 exclusion visibility: WARN at decision time; validation_log
                // remains the durable record.
                log.warn("excluded stage=CTV arrival={} seq={} e2e={} reason=CTV_{}",
                        arrivalId, row.getSequence(), row.getE2e(), outcome.name());
            }
            batch.add(ValidationLogEntity.of(arrivalId, row.getSequence(), outcome.name()));
        }
        verdictBatch.upsertAll(batch);
        return fails;
    }
}
