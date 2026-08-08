package za.co.fnb.dcre.ctv.data.repo;

import java.io.Serial;

/**
 * TECHNICAL failure of a reference read: the datasource could not be reached, or the
 * relation the read names does not exist in this database. It is deliberately NOT a
 * {@code CtvOutcome}, because "I could not look" is not a finding about the collection.
 *
 * <p>SCRUM-107. The account tier fails closed on BOTH flows now, so an EMPTY result
 * means "this account is not in the reference store" and produces
 * {@code FAIL_ACCOUNT_NOT_FOUND}. That makes the two outcomes adjacent and easy to
 * conflate, and conflating them is the expensive direction: a failed read degrading to
 * an empty map would reject an entire arrival on the strength of a connection error,
 * while reporting business rejections an operator would act on. This type keeps them
 * apart at the type level rather than by convention. It replaces a bare
 * {@code IllegalStateException("as-of reference read failed")}, which was
 * indistinguishable from any other defect at the catch site and carried neither the
 * relation nor the SQLSTATE.
 *
 * <p>It is a plain {@link RuntimeException} on purpose, NOT a
 * {@code TransientDataAccessException}: {@code CrdbRetryExceptionHandler} retries only
 * transient 40001 aborts, so this propagates on the first occurrence and fails the step,
 * and the job flow turns that into a FAILED job for AGT to relaunch. An absent relation
 * is not made present by five more attempts, and a datasource outage outlives the step's
 * backoff budget; halting loudly is the honest answer to both.
 */
public class ReferenceUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ReferenceUnavailableException(final String relation, final Throwable cause) {
        super("reference read failed against relation '%s': the store is unavailable or the relation"
                .formatted(relation) + " does not exist in this database", cause);
    }
}
