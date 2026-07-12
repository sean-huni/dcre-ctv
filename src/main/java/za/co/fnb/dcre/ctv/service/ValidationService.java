package za.co.fnb.dcre.ctv.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.ctv.data.model.TxEntryView;
import za.co.fnb.dcre.ctv.data.model.TxHeaderView;
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;
import za.co.fnb.dcre.ctv.data.repo.AccountRepo;
import za.co.fnb.dcre.ctv.data.repo.MandateRepo;
import za.co.fnb.dcre.ctv.data.repo.TxEntryViewRepo;
import za.co.fnb.dcre.ctv.data.repo.TxHeaderViewRepo;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogRepo;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;
import za.co.fnb.dcre.ctv.service.VerdictChain.Entry;
import za.co.fnb.dcre.ctv.service.VerdictChain.Mandate;
import za.co.fnb.dcre.platform.model.CtvOutcome;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business tier (configuration.md point 21): the R-19 two-tier verdict pass
 * over one arrival, against a single as-of snapshot (Fugu F51), persisted via
 * the data/repo tier only. Upserts keyed (arrival_id, sequence): R-05.
 */
@Service
public class ValidationService {

    public sealed interface Result {
        record FileFatal(String reason) implements Result { }
        record Verdicts(boolean anyFail) implements Result { }
    }

    private final TxHeaderViewRepo headers;
    private final TxEntryViewRepo entries;
    private final AccountRepo accounts;
    private final MandateRepo mandates;
    private final ValidationLogRepo verdicts;
    private final boolean dcFlow;

    public ValidationService(TxHeaderViewRepo headers, TxEntryViewRepo entries, AccountRepo accounts,
                             MandateRepo mandates, ValidationLogRepo verdicts,
                             @Value("${dcre.flow-dc:true}") boolean dcFlow) {
        this.headers = headers;
        this.entries = entries;
        this.accounts = accounts;
        this.mandates = mandates;
        this.verdicts = verdicts;
        this.dcFlow = dcFlow;
    }

    public Result validate(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        LocalDate today = LocalDate.parse(header.getBusinessDate().strip(), DateTimeFormatter.BASIC_ISO_DATE);

        List<TxEntryView> spine = entries.findByArrivalIdOrderBySequence(arrivalId);
        if (spine.size() != header.getTxCount()) {
            return new Result.FileFatal("spine count " + spine.size() + " != declared " + header.getTxCount());
        }

        Map<String, Account> accountsByNumber = new HashMap<>();
        accounts.findAll().forEach(a -> accountsByNumber.put(a.getAccountNumber(),
                new Account(a.getAccountNumber(), a.getProductCode(), a.getBalance(),
                        a.getMaxCreditLimit(), a.getProcessStatus())));
        Map<String, List<Mandate>> mandatesByAccount = new HashMap<>();
        for (var m : mandates.findAllByOrderByMandateRef()) {
            mandatesByAccount.computeIfAbsent(m.getCreditorAccount(), k -> new java.util.ArrayList<>())
                    .add(new Mandate(m.getContractRef(), m.getStatus(), m.getStartDate(),
                            m.getExpiryDate(), m.getMaxCollectionAmount()));
        }

        Set<String> seen = new HashSet<>();
        boolean anyFail = false;
        for (TxEntryView row : spine) {
            Entry entry = new Entry(row.getSequence(), row.getE2e(), row.getCreditorAccount(),
                    row.getContractRef(), row.getAmount());
            CtvOutcome outcome = VerdictChain.classify(entry, accountsByNumber, mandatesByAccount,
                    seen, today, dcFlow);
            anyFail |= outcome != CtvOutcome.PASS;
            verdicts.upsert(ValidationLogEntity.of(arrivalId, row.getSequence(), outcome.name()));
        }
        return new Result.Verdicts(anyFail);
    }
}
