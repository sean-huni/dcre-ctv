package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * M10 T15 (SCRUM-78, Sean directive: key on MANDATE_REF): the projection-mode
 * mandate reader. Mirrors {@link ReferenceSnapshotDao}'s CockroachDB
 * {@code AS OF SYSTEM TIME} pattern, but against the SECOND (dcre_man) datasource
 * and the MSR-owned grants-based {@code man_ctv_view} contract view (R-10),
 * looked up by {@code mandate_ref}. It is a READ-ONLY consumer of the projection:
 * every statement is an as-of read, which CockroachDB makes read-only, and the ctv
 * role holds only {@code SELECT} on the view.
 *
 * <p>Like the collections reference snapshot, the mandate snapshot is captured ONCE
 * (at headerCheck) via {@link #snapshotTimestamp()} and shared by every partition
 * range, so a projection mutation landing mid-run is not seen: all ranges read the
 * same MVCC view. Because the snapshot is captured on the dcre_man connection it is
 * that store's own HLC, independent of the collections snapshot. The {@code asOf}
 * timestamp is therefore an explicit parameter on every read (the shared snapshot),
 * never captured per-call.
 */
public class MandateProjectionDao {

    /** cluster_logical_timestamp() is a plain HLC decimal; guard before inlining. */
    private static final Pattern HLC_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    private static final String COLUMNS =
            "mandate_ref, contract_ref, creditor_account, state, start_date, expiry_date, max_collection_amount";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MandateProjectionDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.dataSource = jdbc.getDataSource();
    }

    /** dcre_man HLC captured at headerCheck; every range reads man_ctv_view AS OF this. */
    public String snapshotTimestamp() {
        return jdbc.queryForObject("SELECT cluster_logical_timestamp()::STRING", String.class);
    }

    /** One projection row by its {@code mandateRef}, read AS OF the shared snapshot. */
    public Optional<MandateProjection> findByMandateRef(final String asOf, final String mandateRef) {
        return Optional.ofNullable(byMandateRef(asOf, java.util.List.of(mandateRef)).get(mandateRef));
    }

    /**
     * The projection rows for the given mandate_refs, keyed by mandate_ref, read from
     * {@code man_ctv_view} AS OF the captured snapshot. The view's {@code state} column
     * IS the projection gate - the caller compares it to {@code ACCP}.
     */
    public Map<String, MandateProjection> byMandateRef(final String asOf,
                                                       final Collection<String> mandateRefs) {
        Map<String, MandateProjection> byRef = new HashMap<>();
        if (mandateRefs.isEmpty()) {
            return byRef;
        }
        String sql = "SELECT " + COLUMNS
                + " FROM man_ctv_view AS OF SYSTEM TIME '" + requireHlc(asOf) + "' "
                + "WHERE mandate_ref IN (" + placeholders(mandateRefs.size()) + ")";
        query(sql, mandateRefs, rs -> {
            MandateProjection projection = map(rs);
            byRef.put(projection.mandateRef(), projection);
        });
        return byRef;
    }

    private static MandateProjection map(final ResultSet rs) throws SQLException {
        Date start = rs.getDate("start_date");
        Date expiry = rs.getDate("expiry_date");
        return new MandateProjection(rs.getString("mandate_ref"), rs.getString("contract_ref"),
                rs.getString("creditor_account"), rs.getString("state"),
                start == null ? null : start.toLocalDate(),
                expiry == null ? null : expiry.toLocalDate(),
                rs.getBigDecimal("max_collection_amount"));
    }

    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private void query(final String sql, final Collection<String> params, final RowConsumer consumer) {
        // Own connection: the AS OF read must not join any read-write transaction.
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
            throw new IllegalStateException("as-of mandate projection read failed", e);
        }
    }

    private static String placeholders(final int count) {
        return IntStream.range(0, count).mapToObj(n -> "?").collect(Collectors.joining(","));
    }

    private static String requireHlc(final String asOf) {
        if (asOf == null || !HLC_DECIMAL.matcher(asOf).matches()) {
            throw new IllegalArgumentException("invalid as-of timestamp: " + asOf);
        }
        return asOf;
    }
}
