package za.co.fnb.dcre.ctv.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;
import za.co.fnb.dcre.ctv.service.VerdictChain.Entry;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;
import za.co.fnb.dcre.platform.model.CtvOutcome;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCRUM-107 repair: the account tier is a fail-CLOSED control on BOTH flows. The chain
 * is DB-free, so the "no matching row" arm is asserted here directly rather than
 * inferred from a job run.
 *
 * <p>The ENDO half is the defect this test exists for. It was the twin of the one found
 * in {@code payments/ptv}, whose verdict chain is a fork of this one: an account the
 * reference store does not hold returned PASS on ENDO, on the A-20 draft reasoning that
 * the account would be created downstream. A control that answers PASS when it found
 * nothing is not a control, and it fails silently, because nothing errors and no suite
 * goes red.
 *
 * <p>The companion technical arm (the store could not be read at all) cannot appear here
 * by construction: it never reaches the chain, because
 * {@code ReferenceSnapshotDao} raises {@code ReferenceUnavailableException} before a map
 * is ever built. That structural separation IS the distinction the repair is about, and
 * it is asserted in {@code ReferenceSnapshotDaoTechnicalFailureTest}.
 */
class VerdictChainAccountTierTest {

    private static final String ACCOUNT = "62999999999901";

    private static final Map<String, MandateProjection> NO_PROJECTION = Map.of();

    private static Entry entry() {
        return new Entry(1, "E2E-UNIT-1", ACCOUNT, "CT-UNIT-1", null, new BigDecimal("100.00"));
    }

    @Test
    void noMatchingRowIsABusinessRejectionOnEndo() {
        assertEquals(CtvOutcome.FAIL_ACCOUNT_NOT_FOUND,
                VerdictChain.classify(entry(), Map.of(), NO_PROJECTION, false),
                "an account absent from the reference store is a rejection with its own reason on"
                        + " ENDO too; a control that answers PASS when it found nothing is not a control");
    }

    @Test
    void noMatchingRowIsABusinessRejectionOnDc() {
        assertEquals(CtvOutcome.FAIL_ACCOUNT_NOT_FOUND,
                VerdictChain.classify(entry(), Map.of(), NO_PROJECTION, true),
                "unchanged on DC: the two flows now agree, which is the point of the repair");
    }

    @Test
    void aMatchedActiveAccountUnderCapStillPassesOnEndo() {
        // Control: proves the rejections above are the ABSENCE arm, not a chain that
        // has started rejecting everything handed to it.
        Account account = new Account(ACCOUNT, "FNBRF", new BigDecimal("500.00"), null, "ACTIVE");
        assertEquals(CtvOutcome.PASS,
                VerdictChain.classify(entry(), Map.of(ACCOUNT, account), NO_PROJECTION, false));
    }

    @Test
    void anExistingAccountWithAnUnsetCapStillPassesOnEndo() {
        // DELIBERATELY UNCHANGED by the repair, and not an oversight. This row EXISTS:
        // existence and activity have both been checked and passed, and only its limit
        // is unset, which is a different question from existence. It is exactly what
        // payments/ptv does after its own repair. Whether an unset cap should itself be
        // a rejection is an account-model question, recorded not decided.
        Account account = new Account(ACCOUNT, "FNBCC", null, null, "ACTIVE");
        assertEquals(CtvOutcome.PASS,
                VerdictChain.classify(entry(), Map.of(ACCOUNT, account), NO_PROJECTION, false));
    }

    @Test
    void anExistingAccountWithAnUnsetCapKeepsItsPreExistingDcOutcome() {
        // Also deliberately unchanged: the DC arm here is pre-existing oracle behaviour
        // (R-35), not part of this repair.
        Account account = new Account(ACCOUNT, "FNBCC", null, null, "ACTIVE");
        assertEquals(CtvOutcome.FAIL_ACCOUNT_NOT_FOUND,
                VerdictChain.classify(entry(), Map.of(ACCOUNT, account), NO_PROJECTION, true));
    }
}
