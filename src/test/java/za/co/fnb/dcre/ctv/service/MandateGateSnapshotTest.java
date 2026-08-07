package za.co.fnb.dcre.ctv.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import za.co.fnb.dcre.ctv.data.repo.MandateProjectionDao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-107 (review C1b): the ENDO flow must not open a dcre_man connection.
 *
 * <p>{@code VerdictChain.classify} returns PASS for an ENDO entry before it looks at the
 * projection, so the snapshot captured at headerCheck is never read on that flow. An
 * unguarded capture would still contact dcre_man on every ENDO arrival, which would make
 * collections-ENDO unavailable whenever the mandates database is unreachable: an outage
 * taken for a store the flow does not consult.
 *
 * <p>The stub DAO THROWS instead of returning a value, so the test fails if the gate
 * merely ignores the result rather than skipping the call. A stub returning a timestamp
 * would pass whether or not the connection was opened, which is the assertion that would
 * have been true while the defect was present.
 */
class MandateGateSnapshotTest {

    /** Stands in for the projection store being unreachable. */
    private static final class UnreachableProjectionDao extends MandateProjectionDao {
        private UnreachableProjectionDao() {
            // A JdbcTemplate over a closed port. Constructing a DataSource opens no
            // connection, so this never contacts anything; the override below is what
            // stands in for the store being unreachable.
            super(new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:1/dcre_man?sslmode=disable")));
        }

        @Override
        public String snapshotTimestamp() {
            throw new IllegalStateException("dcre_man must not be contacted on this flow");
        }
    }

    private static MandateGate gate() {
        return new MandateGate("projection", new UnreachableProjectionDao());
    }

    @Test
    void endoNeverContactsTheProjectionStore() {
        assertThat(gate().snapshot(false)).isEmpty();
    }

    @Test
    void dcStillCapturesTheProjectionSnapshot() {
        // The control: same gate, same stub. DC must reach the store, which proves the
        // ENDO result above came from the guard and not from a gate that never calls.
        assertThatThrownBy(() -> gate().snapshot(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be contacted");
    }
}
