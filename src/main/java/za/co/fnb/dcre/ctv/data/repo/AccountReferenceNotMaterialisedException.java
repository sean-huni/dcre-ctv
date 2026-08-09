package za.co.fnb.dcre.ctv.data.repo;

import java.io.Serial;

/**
 * TECHNICAL failure: {@code dcre_col.account} has never been materialised in this
 * database, so there is nothing for the verdict chain to judge against.
 *
 * <p><b>Why this type has to exist.</b> An empty {@code account} table and an account the
 * artifact genuinely does not carry are indistinguishable to
 * {@link ReferenceSnapshotDao#accountsByNumber}: both produce an empty map, and
 * {@code VerdictChain} turns an empty map into {@code FAIL_ACCOUNT_NOT_FOUND}. That is
 * correct for one absent account and catastrophic for an unloaded table, because every
 * row of the arrival is then rejected with a BUSINESS reason. It is loud per transaction
 * and completely misleading in aggregate: it reads as a data-quality problem in the input
 * file when the real cause is that a deployment step never happened, and nothing in the
 * log says "the reference data was never loaded". This exception says exactly that, and
 * names the step that was skipped.
 *
 * <p><b>The authority is {@code account_reference_load}, not a row count.</b> That table
 * gets one row per successful load, written in the SAME transaction as the data it
 * describes, so its newest row is a statement about the table's CURRENT contents. Counting
 * {@code account} instead would answer a different question: a legitimately empty
 * projection and an absent load are the same number.
 *
 * <p>A plain {@link RuntimeException} on purpose, and deliberately NOT a
 * {@code TransientDataAccessException}: {@code CrdbRetryExceptionHandler} retries only
 * transient 40001 aborts, and a table nothing has loaded is not loaded by five more
 * attempts. It propagates on the first occurrence, fails the step, and becomes a FAILED
 * job an operator can see, which is the honest answer. Sibling of
 * {@link ReferenceUnavailableException}: that one is "I could not look", this one is
 * "I looked, and nobody has ever put anything here".
 */
public class AccountReferenceNotMaterialisedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** How an operator makes it true. Named in every message: the fix is a step, not a retry. */
    private static final String REMEDY =
            "stage the versioned artifact with infra/dcre-infra/scripts/materialise-account-reference.sh"
                    + " (run by scripts/cutover-v1.sh and scripts/env-reset.sh), then run the loader with"
                    + " DCRE_CTV_JOB_NAME=accountReferenceLoadJob";

    private AccountReferenceNotMaterialisedException(final String message) {
        super(message);
    }

    /** No {@code account_reference_load} row at all: the loader has never run here. */
    public static AccountReferenceNotMaterialisedException neverLoaded() {
        return new AccountReferenceNotMaterialisedException(
                "account reference has NEVER been materialised into this database: account_reference_load"
                        + " is empty, so dcre_col.account holds no rows and every verdict would reject with"
                        + " FAIL_ACCOUNT_NOT_FOUND for a reason that is not about the input file. To fix, "
                        + REMEDY);
    }

    /** A load ran and applied nothing: the table is empty for a non-business reason all the same. */
    public static AccountReferenceNotMaterialisedException loadedEmpty(final String datasetVersion) {
        return new AccountReferenceNotMaterialisedException(
                "account reference load '%s' applied ZERO rows, so dcre_col.account is empty and every"
                        .formatted(datasetVersion)
                        + " verdict would reject with FAIL_ACCOUNT_NOT_FOUND for a reason that is not about"
                        + " the input file. The artifact carried no COLLECTIONS-shape rows for this"
                        + " context. To fix, " + REMEDY);
    }
}
