package za.co.fnb.dcre.ctv.data.repo;

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
 */
@Component
public class ReferenceSnapshotDao {

    /** cluster_logical_timestamp() is a plain HLC decimal; guard before inlining. */
    private static final Pattern HLC_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

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
                + "FROM account AS OF SYSTEM TIME '" + requireHlc(asOf) + "' "
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
            throw new IllegalStateException("as-of reference read failed", e);
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
