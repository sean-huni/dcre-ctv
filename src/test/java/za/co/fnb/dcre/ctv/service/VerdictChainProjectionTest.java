package za.co.fnb.dcre.ctv.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;
import za.co.fnb.dcre.ctv.service.VerdictChain.Entry;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;
import za.co.fnb.dcre.platform.model.CtvOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCRUM-107: the DC-flow mandate gate, which now has exactly ONE backing. The
 * projection is looked up by the
 * entry's {@code mandateRef} (never the account/contract pair) and its state IS
 * the gate (R-10): only {@code ACCP} may be collected against; every other state
 * and an absent projection row reject with {@code FAIL_MANDATE_NOT_ACTIVE}. An
 * entry with a NULL mandate_ref (a collection not targeting a mandate, e.g. an
 * old-layout V1/V2 book) is a mandate-gate no-op: the account/cap tier stands but
 * the mandate tier passes. ENDO (dcFlow=false) has no mandate gate at all.
 */
class VerdictChainProjectionTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-07-11");
    private static final String ACCOUNT = "63000000000001";
    private static final String CONTRACT = "CTR-0001";
    private static final String MANDATE = "MND-0001";

    /** ACTIVE credit-card account with ample limit: only the mandate gate can fail. */
    private static final Map<String, Account> ACCOUNTS = Map.of(
            ACCOUNT, new Account(ACCOUNT, "FNBCC", null, new BigDecimal("100000.00"), "ACTIVE"));

    private static final Map<String, MandateProjection> NO_PROJECTION = Map.of();

    private static Entry entry() {
        return new Entry(1, "E2E-0001", ACCOUNT, CONTRACT, MANDATE, new BigDecimal("100.00"));
    }

    private static Entry entryWithoutMandateRef() {
        return new Entry(1, "E2E-0001", ACCOUNT, CONTRACT, null, new BigDecimal("100.00"));
    }

    /** A projection row keyed by mandate_ref, in the given state. */
    private static Map<String, MandateProjection> projectionByRef(final String state) {
        return Map.of(MANDATE, new MandateProjection(MANDATE, CONTRACT, ACCOUNT, state,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
                new BigDecimal("100000.00")));
    }

    private static CtvOutcome projection(final Entry entry, final Map<String, MandateProjection> byRef) {
        return VerdictChain.classify(entry, ACCOUNTS, byRef, true);
    }

    @Test
    void projectionAccpPassesTheMandateGate() {
        assertEquals(CtvOutcome.PASS, projection(entry(), projectionByRef("ACCP")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PDNG", "SUSPENDED", "CANC", "RJCT", "EXPIRED"})
    void projectionNonAccpStatesRejectAsNotActive(final String state) {
        assertEquals(CtvOutcome.FAIL_MANDATE_NOT_ACTIVE, projection(entry(), projectionByRef(state)),
                "projection state " + state + " is not collectable");
    }

    @Test
    void projectionAbsentMandateRejectsAsNotActive() {
        assertEquals(CtvOutcome.FAIL_MANDATE_NOT_ACTIVE, projection(entry(), NO_PROJECTION));
    }

    @Test
    void projectionLooksUpByMandateRefNotAccount() {
        // A projection row exists for the account, ACCP, but under a DIFFERENT
        // mandate_ref: keyed by mandate_ref the entry's ref is absent -> reject.
        Map<String, MandateProjection> otherRef = Map.of("MND-OTHER",
                new MandateProjection("MND-OTHER", CONTRACT, ACCOUNT, "ACCP",
                        LocalDate.parse("2026-01-01"), null, new BigDecimal("100000.00")));
        assertEquals(CtvOutcome.FAIL_MANDATE_NOT_ACTIVE, projection(entry(), otherRef));
    }

    @Test
    void projectionNullMandateRefIsAMandateGateNoOp() {
        // A collection not targeting a mandate (NULL mandate_ref): the mandate
        // gate is a no-op; the account/cap tier already passed, so the row passes.
        assertEquals(CtvOutcome.PASS, projection(entryWithoutMandateRef(), NO_PROJECTION));
    }

    @Test
    void endoFlowHasNoMandateGate() {
        // dcFlow=false short-circuits before the mandate layer (R-20): the
        // projection gate never runs for collections-endo.
        CtvOutcome outcome = VerdictChain.classify(entry(), ACCOUNTS, NO_PROJECTION, false);
        assertEquals(CtvOutcome.PASS, outcome);
    }
}
