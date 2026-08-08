package za.co.fnb.dcre.ctv.service;

import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.platform.model.ProductType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * The R-19 item-tier precedence chain, ported from the fixture toolkit's
 * verifier classify() (the dev-normative oracle under R-35):
 * account exists -> account active -> account cap -> mandate state.
 * Mandate layer applies to the DC flow only.
 * Duplicate rules (e2e and content hash) run BEFORE this chain as the
 * set-based SQL dup scan (DupScanService, R-41); rows verdicted there are
 * never re-classified here.
 * ENDO delta (A-20 draft, SCRUM-32) [SYNTHETIC-CONTRACT R-35]: an EXISTING
 * account whose cap is unset passes through (the cap check applies post-init);
 * everything else, including over-cap on existing accounts, is unchanged.
 * An UNKNOWN account does NOT pass through, on either flow: see
 * {@link #accountTier} for the SCRUM-107 repair and why the two arms differ.
 *
 * <p>SCRUM-107: the mandate layer has exactly ONE backing, the mandates-owned
 * projection {@code dcre_man.man_ctv_view}, looked up by the entry's
 * {@code mandateRef}. The whole mandate layer is a single state check: the
 * accepted-mandate lifecycle (cancel, reject, suspend, expire) is already folded
 * into the projection state, so only {@code ACCP} may be collected against and
 * every other state or an absent projection row rejects with
 * {@code FAIL_MANDATE_NOT_ACTIVE}. An entry with a NULL mandate_ref (a collection
 * not targeting a mandate, e.g. an old-layout V1/V2 book) is a mandate-gate
 * no-op: the account/cap tier stands but the mandate tier passes.
 *
 * <p>The former {@code LEGACY} backing read {@code dcre_col.mandate}, a mandates
 * table that lived in the collections database and that no service owned. It was
 * dropped in {@code 2026/08/001-drop-local-mandate.xml} after the projection gate
 * was proven on the cluster with BOTH verdicts, and its status/effective/expiry/cap
 * chain went with it. That is why {@code FAIL_MANDATE_NOT_FOUND},
 * {@code FAIL_CONTRACT_MISMATCH}, {@code FAIL_MANDATE_NOT_EFFECTIVE},
 * {@code FAIL_MANDATE_EXPIRED} and {@code FAIL_EXCEEDS_MANDATE_CAP} are no longer
 * reachable on the DC flow: the projection collapses all of them into the single
 * state predicate above.
 */
public final class VerdictChain {

    public record Account(String accountNumber, String productCode, BigDecimal balance,
                          BigDecimal maxCreditLimit, String processStatus) {
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
                                      Map<String, MandateProjection> projectionByRef,
                                      boolean dcFlow) {
        CtvOutcome accountOutcome = accountTier(entry, accounts, dcFlow);
        if (accountOutcome != null) {
            return accountOutcome;
        }
        if (!dcFlow) {
            return CtvOutcome.PASS; // ENDO has no mandate gate (R-20)
        }
        return projectionMandateVerdict(entry, projectionByRef);
    }

    /**
     * Account existence/active/cap tier. Returns a terminal outcome, or {@code null}
     * when the account tier passes and the mandate tier should decide.
     *
     * <p><b>SCRUM-107: the existence check fails CLOSED on both flows.</b> An account the
     * reference store does not hold is {@link CtvOutcome#FAIL_ACCOUNT_NOT_FOUND} on ENDO
     * as well as on DC. It previously returned PASS on ENDO, on the A-20 draft reasoning
     * that the account would be created downstream (create-if-absent, R-11), which left
     * the first tier of the chain answering PASS in exactly the case it exists to catch.
     * A control that passes when it finds nothing is not a control, and it fails
     * silently: nothing errors and no suite goes red. The identical defect was found in
     * {@code payments/ptv}, whose chain is a fork of this one, and is repaired the same
     * way in both.
     *
     * <p><b>"Absent" is not "unreadable".</b> This method only ever sees a map, so it
     * cannot tell the difference, and it does not have to: a reference store that could
     * not be read never produces a map at all.
     * {@code ReferenceSnapshotDao} raises {@code ReferenceUnavailableException} and the
     * step fails, so a technical fault becomes a FAILED job rather than an arrival's
     * worth of business rejections. The separation is structural, not a convention this
     * class has to remember.
     *
     * <p><b>The unset-cap arm below is DELIBERATELY unchanged by that repair.</b> On ENDO
     * an EXISTING account with an unset cap still passes: the row is present, existence
     * and activity have both been checked, and only its limit is unset, which is a
     * different question from existence. That is exactly what {@code payments/ptv} does
     * after its own repair. The DC arm there is pre-existing oracle behaviour
     * ([SYNTHETIC-CONTRACT R-35]), not part of this repair. Whether an unset cap should
     * itself be a rejection is an account-model question, recorded not decided.
     */
    private static CtvOutcome accountTier(Entry entry, Map<String, Account> accounts, boolean dcFlow) {
        Account account = accounts.get(entry.creditorAccount());
        if (account == null) {
            // No row for this creditor account in the snapshot: a business REJECTION with
            // its own reason, on BOTH flows. The map is only ever built from a read that
            // SUCCEEDED, so an absence here is a fact about the store's contents, never
            // about its reachability (see the javadoc above).
            return CtvOutcome.FAIL_ACCOUNT_NOT_FOUND;
        }
        if (!"ACTIVE".equals(account.processStatus())) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_ACTIVE;
        }
        boolean balanceCarrying = ProductType.fromProductCode(account.productCode()) == ProductType.BALANCE_CARRYING;
        BigDecimal cap = balanceCarrying ? account.balance() : account.maxCreditLimit();
        if (cap == null) {
            // UNCHANGED by the SCRUM-107 repair, and stated so rather than quietly left:
            // [SYNTHETIC-CONTRACT R-35] A-20 draft. On ENDO the row EXISTS and its limit
            // is unset, so there is no cap to breach; unlike the absence arm above this
            // is not the tier failing open, because existence and activity have both
            // been checked and passed. DC keeps the oracle's FAIL_ACCOUNT_NOT_FOUND.
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

}
