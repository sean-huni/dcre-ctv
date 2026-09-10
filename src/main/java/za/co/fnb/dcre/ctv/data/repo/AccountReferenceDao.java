package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.co.fnb.dcre.ctv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.ctv.domain.AccountReferenceRow;

import java.sql.Timestamp;
import java.util.List;

/**
 * The write half of the loader against {@code dcre_col}: replace every row of
 * {@code account} with this artifact's projection, and record what was consumed.
 *
 * <p><b>There is no partial application and no per-row skip.</b> The delete, the inserts
 * and the load record are three statements of ONE transaction, opened by the caller (the
 * Batch step). A constraint violation on any row therefore rolls the whole thing back and
 * the table keeps its PREVIOUS contents, which is the only outcome an operator can reason
 * about: a table half-way between two datasets matches no {@code account_reference_load}
 * row and describes nothing.
 *
 * <p>The load record is written in that same transaction on purpose. Committed separately
 * it would be a second place holding one fact, and one of the two would be stale with
 * nothing to say which.
 */
@Repository
public class AccountReferenceDao {

    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (account_number, product_code, status, app_no, acc_type, branch_code,
                                 balance, max_credit_limit, cancel_reason, country_id, edr_ind,
                                 pre_ind, process_status, status_reason, ucn, client_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    private static final String INSERT_LOAD = """
            INSERT INTO account_reference_load (dataset_version, schema_version, source_id,
                                                effective_ts, publication_ts, row_count, checksum,
                                                applied_row_count, job_execution_id)
            VALUES (?,?,?,?,?,?,?,?,?)""";

    private final JdbcTemplate jdbc;

    public AccountReferenceDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Caller supplies the transaction; see the class javadoc for why that is the contract. */
    public void replaceAll(final List<AccountReferenceRow> rows) {
        jdbc.update("DELETE FROM account");
        jdbc.batchUpdate(INSERT_ACCOUNT, rows.stream().map(AccountReferenceDao::parameters).toList());
    }

    public void recordLoad(final AccountReferenceManifest manifest, final int appliedRowCount,
                           final Long jobExecutionId) {
        jdbc.update(INSERT_LOAD, manifest.datasetVersion(), manifest.schemaVersion(),
                manifest.sourceId(), Timestamp.from(manifest.effectiveTs()),
                Timestamp.from(manifest.publicationTs()), manifest.rowCount(), manifest.checksum(),
                appliedRowCount, jobExecutionId);
    }

    private static Object[] parameters(final AccountReferenceRow row) {
        return new Object[]{row.accountNumber(), row.productCode(), row.status(), row.appNo(),
                row.accType(), row.branchCode(), row.balance(), row.maxCreditLimit(),
                row.cancelReason(), row.countryId(), row.edrInd(), row.preInd(),
                row.processStatus(), row.statusReason(), row.ucn(), row.clientId()};
    }
}
