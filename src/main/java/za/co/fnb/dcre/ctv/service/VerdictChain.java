package za.co.fnb.dcre.ctv.service;

import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.platform.model.ProductType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The R-19 item-tier precedence chain, ported from the fixture toolkit's
 * verifier classify() (the dev-normative oracle under R-35):
 * dup e2e -> account exists -> account active -> account cap ->
 * mandate exists -> contract match -> mandate status -> effective ->
 * expiry -> mandate cap. Mandate layer applies to the DC flow only.
 */
public final class VerdictChain {

    public record Account(String accountNumber, String productCode, BigDecimal balance,
                          BigDecimal maxCreditLimit, String processStatus) {
    }

    public record Mandate(String contractRef, String status, LocalDate startDate,
                          LocalDate expiryDate, BigDecimal maxCollectionAmount) {
    }

    public record Entry(int sequence, String e2e, String creditorAccount,
                        String contractRef, BigDecimal amount) {
    }

    private VerdictChain() {
    }

    public static CtvOutcome classify(Entry entry, Map<String, Account> accounts,
                                      Map<String, List<Mandate>> mandatesByAccount,
                                      Set<String> seenE2e, LocalDate today, boolean dcFlow) {
        if (!seenE2e.add(entry.e2e())) {
            return CtvOutcome.FAIL_DUPLICATE_E2E; // first-wins, in-file scope (R-25)
        }
        Account account = accounts.get(entry.creditorAccount());
        if (account == null) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_FOUND;
        }
        if (!"ACTIVE".equals(account.processStatus())) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_ACTIVE;
        }
        boolean balanceCarrying = ProductType.fromProductCode(account.productCode()) == ProductType.BALANCE_CARRYING;
        BigDecimal cap = balanceCarrying ? account.balance() : account.maxCreditLimit();
        if (cap == null) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_FOUND;
        }
        if (entry.amount().compareTo(cap) > 0) {
            return balanceCarrying ? CtvOutcome.FAIL_EXCEEDS_RF_BALANCE : CtvOutcome.FAIL_EXCEEDS_CC_LIMIT;
        }
        if (!dcFlow) {
            return CtvOutcome.PASS; // ENDO has no mandate gate (R-20)
        }
        List<Mandate> held = mandatesByAccount.get(entry.creditorAccount());
        if (held == null || held.isEmpty()) {
            return CtvOutcome.FAIL_MANDATE_NOT_FOUND;
        }
        Mandate mandate = held.stream()
                .filter(m -> m.contractRef().equals(entry.contractRef()))
                .findFirst().orElse(null);
        if (mandate == null) {
            return CtvOutcome.FAIL_CONTRACT_MISMATCH; // distinct from NOT_FOUND (R-23)
        }
        if (!"ACTIVE".equals(mandate.status())) {
            return CtvOutcome.FAIL_MANDATE_NOT_ACTIVE;
        }
        if (mandate.startDate().isAfter(today)) {
            return CtvOutcome.FAIL_MANDATE_NOT_EFFECTIVE;
        }
        if (mandate.expiryDate() != null && mandate.expiryDate().isBefore(today)) {
            return CtvOutcome.FAIL_MANDATE_EXPIRED;
        }
        if (entry.amount().compareTo(mandate.maxCollectionAmount()) > 0) {
            return CtvOutcome.FAIL_EXCEEDS_MANDATE_CAP;
        }
        return CtvOutcome.PASS;
    }
}
