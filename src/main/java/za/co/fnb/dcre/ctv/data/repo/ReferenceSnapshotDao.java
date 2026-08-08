package za.co.fnb.dcre.ctv.data.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.service.VerdictChain.Account;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * R-41 F51 fix: one consistent as-of snapshot of the account/mandate reference
 * stores across every partition worker. The verdict phase runs in N independent
 * read-write transactions, so a naive per-range read of a mutating store could
 * split verdicts across ranges. This DAO reads each range's reference rows via
 * CockroachDB {@code AS OF SYSTEM TIME} at a single timestamp captured once at
 * headerCheck ({@link #snapshotTimestamp()}), pinning all ranges to the same
 * MVCC view.
 *
 * <p>The as-of reads run on their OWN pooled connection (not the tasklet's
 * read-write transaction): CockroachDB requires AS OF SYSTEM TIME to be the
 * first statement of its transaction and makes that transaction read-only, so
 * it cannot share the verdict-writing transaction.
 *
 * <p><b>SCRUM-107.</b> The account master is {@code dcre_col.account}, on the PRIMARY
 * collections datasource, and since this wave CTV's own Liquibase changelog creates it
 * and CTV's own loader job fills it from the versioned reference artifact. It is no
 * longer read out of a shared reference database over a second connection.
 *
 * <p>An empty result is now a BUSINESS signal: the chain turns it into
 * {@code FAIL_ACCOUNT_NOT_FOUND} on both flows. A read that could not RUN therefore must
 * never degrade to an empty map, or an outage would be reported as an arrival's worth of
 * business rejections. Every failure raises {@link ReferenceUnavailableException} and is
 * logged at ERROR under its own token with the relation and the SQLSTATE, so an operator
 * can tell the two apart in the log as well as in the outcome.
 */
@Component
public class ReferenceSnapshotDao {

    /** The account master relation. Named once: it is in the SQL, the log and the exception. */
    static final String ACCOUNT_RELATION = "account";

    /** cluster_logical_timestamp() is a plain HLC decimal; guard before inlining. */
    private static final Pattern HLC_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    private static final Logger log = LoggerFactory.getLogger(ReferenceSnapshotDao.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ReferenceSnapshotDao(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** HLC captured at headerCheck; every range reads the reference stores AS OF this. */
    public String snapshotTimestamp() {
        return jdbc.queryForObject("SELECT cluster_logical_timestamp()::STRING", String.class);
    }

    public Map<String, Account> accountsByNumber(String asOf, Collection<String> accountNumbers) {
        Map<String, Account> byNumber = new HashMap<>();
        if (accountNumbers.isEmpty()) {
            return byNumber;
        }
        String sql = "SELECT account_number, product_code, balance, max_credit_limit, process_status "
                + "FROM " + ACCOUNT_RELATION + " AS OF SYSTEM TIME '" + requireHlc(asOf) + "' "
                + "WHERE account_number IN (" + placeholders(accountNumbers.size()) + ")";
        query(sql, accountNumbers, rs -> {
            Account account = new Account(rs.getString("account_number"), rs.getString("product_code"),
                    rs.getBigDecimal("balance"), rs.getBigDecimal("max_credit_limit"),
                    rs.getString("process_status"));
            byNumber.put(account.accountNumber(), account);
        });
        return byNumber;
    }

    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private void query(String sql, Collection<String> params, RowConsumer consumer) {
        // Own connection: the AS OF read must not join the tasklet's read-write tx.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (String param : params) {
                ps.setString(i++, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(rs);
                }
            }
        } catch (SQLException e) {
            // TECHNICAL, never a verdict. Logged here rather than at the catch site far
            // above so the SQLSTATE and the relation are on the same line as the token an
            // operator greps for; "42P01 relation does not exist" and "08001 connection
            // refused" are the two shapes this actually takes in dcre_col today.
            log.error("reference-store-unavailable stage=CTV relation={} sqlState={} reason={}",
                    ACCOUNT_RELATION, e.getSQLState(), e.getMessage());
            throw new ReferenceUnavailableException(ACCOUNT_RELATION, e);
        }
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(n -> "?").collect(Collectors.joining(","));
    }

    private static String requireHlc(String asOf) {
        if (asOf == null || !HLC_DECIMAL.matcher(asOf).matches()) {
            throw new IllegalArgumentException("invalid as-of timestamp: " + asOf);
        }
        return asOf;
    }
}
