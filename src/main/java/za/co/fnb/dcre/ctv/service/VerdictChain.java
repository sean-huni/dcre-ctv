package za.co.fnb.dcre.ctv.service;

import za.co.fnb.dcre.ctv.domain.MandateSource;
import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.platform.model.ProductType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The R-19 item-tier precedence chain, ported from the fixture toolkit's
 * verifier classify() (the dev-normative oracle under R-35):
 * account exists -> account active -> account cap ->
 * mandate exists -> contract match -> mandate status -> effective ->
 * expiry -> mandate cap. Mandate layer applies to the DC flow only.
 * Duplicate rules (e2e and content hash) run BEFORE this chain as the
 * set-based SQL dup scan (DupScanService, R-41); rows verdicted there are
 * never re-classified here.
 * ENDO deltas (A-20 draft, SCRUM-32) [SYNTHETIC-CONTRACT R-35]: unknown
 * account and NULL cap pass through (AIS create-if-absent downstream);
 * everything else, including over-cap on existing accounts, is unchanged.
 *
 * <p>M10 T15 (SCRUM-78, Sean directive: key on MANDATE_REF): the mandate layer
 * has two backings selected by {@link MandateSource}. {@code LEGACY} keeps the
 * full dcre_col status/effective/expiry/cap chain matched by contract_ref.
 * {@code PROJECTION} collapses the whole mandate layer to a single state check
 * against the MSR projection ({@code man_ctv_view}) looked up by the entry's
 * {@code mandateRef}: the accepted-mandate lifecycle (cancel, reject, suspend,
 * expire) is already folded into the projection state, so only {@code ACCP} may
 * be collected against and every other state or an absent projection row rejects
 * with {@code FAIL_MANDATE_NOT_ACTIVE}. An entry with a NULL mandate_ref (a
 * collection not targeting a mandate, e.g. an old-layout V1/V2 book) is a
 * mandate-gate no-op: the account/cap tier stands but the mandate tier passes.
 * Account and duplicate tiers are unchanged in either mode.
 */
public final class VerdictChain {

    public record Account(String accountNumber, String productCode, BigDecimal balance,
                          BigDecimal maxCreditLimit, String processStatus) {
    }

    public record Mandate(String contractRef, String status, LocalDate startDate,
                          LocalDate expiryDate, BigDecimal maxCollectionAmount) {
    }

    /** A row of the MSR-owned {@code man_ctv_view}, keyed for lookup by {@code mandateRef}. */
    public record MandateProjection(String mandateRef, String contractRef, String creditorAccount,
                                    String state, LocalDate startDate, LocalDate expiryDate,
                                    BigDecimal maxCollectionAmount) {
    }

    public record Entry(int sequence, String e2e, String creditorAccount,
                        String contractRef, String mandateRef, BigDecimal amount) {
    }

    private VerdictChain() {
    }

    public static CtvOutcome classify(Entry entry, Map<String, Account> accounts,
                                      Map<String, List<Mandate>> mandatesByAccount,
                                      Map<String, MandateProjection> projectionByRef,
                                      LocalDate today, boolean dcFlow, MandateSource mandateSource) {
        CtvOutcome accountOutcome = accountTier(entry, accounts, dcFlow);
        if (accountOutcome != null) {
            return accountOutcome;
        }
        if (!dcFlow) {
            return CtvOutcome.PASS; // ENDO has no mandate gate (R-20)
        }
        return mandateSource == MandateSource.PROJECTION
                ? projectionMandateVerdict(entry, projectionByRef)
                : legacyMandateVerdict(entry, mandatesByAccount, today);
    }

    /**
     * Account existence/active/cap tier (unchanged, both modes). Returns a terminal
     * outcome, or {@code null} when the account tier passes and the mandate tier
     * should decide.
     */
    private static CtvOutcome accountTier(Entry entry, Map<String, Account> accounts, boolean dcFlow) {
        Account account = accounts.get(entry.creditorAccount());
        if (account == null) {
            // [SYNTHETIC-CONTRACT R-35] A-20 draft: on ENDO an unknown account
            // passes through; AIS creates it downstream (create-if-absent).
            return dcFlow ? CtvOutcome.FAIL_ACCOUNT_NOT_FOUND : CtvOutcome.PASS;
        }
        if (!"ACTIVE".equals(account.processStatus())) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_ACTIVE;
        }
        boolean balanceCarrying = ProductType.fromProductCode(account.productCode()) == ProductType.BALANCE_CARRYING;
        BigDecimal cap = balanceCarrying ? account.balance() : account.maxCreditLimit();
        if (cap == null) {
            // [SYNTHETIC-CONTRACT R-35] A-20 draft: on ENDO a NULL cap marks a
            // freshly-creatable account; the cap check applies post-init, so it
            // passes through. DC keeps the oracle's FAIL_ACCOUNT_NOT_FOUND.
            return dcFlow ? CtvOutcome.FAIL_ACCOUNT_NOT_FOUND : CtvOutcome.PASS;
        }
        if (entry.amount().compareTo(cap) > 0) {
            return balanceCarrying ? CtvOutcome.FAIL_EXCEEDS_RF_BALANCE : CtvOutcome.FAIL_EXCEEDS_CC_LIMIT;
        }
        return null;
    }

    /**
     * T15 projection gate: the MSR projection state IS the mandate verdict, looked
     * up by the entry's mandate_ref. A NULL/blank mandate_ref is a no-op (the row
     * does not target a mandate). Otherwise only ACCP is collectable; every other
     * state and an absent projection row reject uniformly (the lifecycle is already
     * folded into the state).
     */
    private static CtvOutcome projectionMandateVerdict(Entry entry,
                                                       Map<String, MandateProjection> projectionByRef) {
        String mandateRef = entry.mandateRef();
        if (mandateRef == null || mandateRef.isBlank()) {
            return CtvOutcome.PASS; // collection not targeting a mandate: gate is a no-op
        }
        MandateProjection projection = projectionByRef.get(mandateRef);
        return projection != null && "ACCP".equals(projection.state())
                ? CtvOutcome.PASS
                : CtvOutcome.FAIL_MANDATE_NOT_ACTIVE;
    }

    /** The legacy dcre_col status/effective/expiry/cap chain, matched by contract_ref (unchanged). */
    private static CtvOutcome legacyMandateVerdict(Entry entry,
                                                   Map<String, List<Mandate>> mandatesByAccount,
                                                   LocalDate today) {
        List<Mandate> held = mandatesByAccount.get(entry.creditorAccount());
        Mandate mandate = held == null ? null : held.stream()
                .filter(m -> m.contractRef().equals(entry.contractRef()))
                .findFirst().orElse(null);
        if (held == null || held.isEmpty()) {
            return CtvOutcome.FAIL_MANDATE_NOT_FOUND;
        }
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
