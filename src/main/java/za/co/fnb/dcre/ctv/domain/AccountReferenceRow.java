package za.co.fnb.dcre.ctv.domain;

import java.math.BigDecimal;

/**
 * One row of the artifact's COLLECTIONS projection: the sixteen data columns of
 * {@code dcre_col.account}, as read from {@code account.csv}.
 *
 * <p>The MANDATES projection of the same artifact is a DIFFERENT shape, not a subset of
 * this one, and the two are never unioned. A MANDATES row carries an
 * {@code account_type_code} and no branch code; a COLLECTIONS row carries a branch code
 * and no account type. That is the truth of the data, and per-context materialisation
 * exists so each family keeps the NOT NULLs its own shape requires rather than relaxing
 * them to admit the other.
 *
 * <p>An empty CSV cell means ABSENT, so it becomes {@code null} for the three nullable
 * columns. It is not permitted for the rest: a blank in a NOT NULL column is a defective
 * artifact and fails the load rather than arriving at the database as an empty string.
 */
public record AccountReferenceRow(String accountNumber, String productCode, String status,
                                  String appNo, String accType, String branchCode,
                                  BigDecimal balance, BigDecimal maxCreditLimit,
                                  String cancelReason, long countryId, boolean edrInd,
                                  boolean preInd, String processStatus, String statusReason,
                                  String ucn, long clientId) {
}
