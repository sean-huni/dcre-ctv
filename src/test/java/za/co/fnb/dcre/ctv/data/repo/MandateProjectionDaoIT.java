package za.co.fnb.dcre.ctv.data.repo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 T15 (SCRUM-78, Sean directive: key on MANDATE_REF): {@link MandateProjectionDao}
 * against a SECOND (dcre_man) CockroachDB connection, reading the MSR-shaped
 * {@code man_ctv_view} by mandate_ref. Proves the view mapping (state), the by-ref
 * lookup for every projection state, and, critically, the CockroachDB
 * {@code AS OF SYSTEM TIME} isolation the projection gate relies on: a projection
 * mutation landing AFTER the snapshot is not seen by a read pinned to that snapshot,
 * exactly as the collections reference snapshot behaves on the primary connection.
 */
class MandateProjectionDaoIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    static MandateProjectionDao dao;
    static JdbcTemplate manJdbc;

    @BeforeAll
    static void seedProjection() {
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword()));
        root.execute("CREATE DATABASE IF NOT EXISTS dcre_man");

        String manUrl = "jdbc:postgresql://" + CRDB.getHost() + ":" + CRDB.getMappedPort(26257)
                + "/dcre_man?sslmode=disable";
        manJdbc = new JdbcTemplate(new DriverManagerDataSource(
                manUrl, CRDB.getUsername(), CRDB.getPassword()));
        // The MSR-owned man-core mandate table shape (state column) + the T15 view.
        manJdbc.execute("""
                CREATE TABLE mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(35) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    start_date DATE NULL,
                    expiry_date DATE NULL,
                    max_collection_amount DECIMAL(18,2) NULL)""");
        manJdbc.execute("""
                CREATE VIEW man_ctv_view AS
                    SELECT mandate_ref, contract_ref, creditor_account, state, start_date,
                           expiry_date, max_collection_amount
                    FROM mandate""");
        insert("MND-ACCP", "C-A", "AAA", "ACCP");
        insert("MND-PDNG", "C-B", "BBB", "PDNG");
        insert("MND-SUSPENDED", "C-C", "CCC", "SUSPENDED");
        insert("MND-CANC", "C-D", "DDD", "CANC");
        insert("MND-RJCT", "C-E", "EEE", "RJCT");
        insert("MND-EXPIRED", "C-F", "FFF", "EXPIRED");
        insert("MND-FLIP", "C-G", "GGG", "ACCP");
        dao = new MandateProjectionDao(manJdbc);
    }

    static void insert(String ref, String contract, String account, String state) {
        manJdbc.update("""
                INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state,
                                     start_date, expiry_date, max_collection_amount)
                VALUES (?,?,?,?, DATE '2026-01-01', DATE '2027-01-01', 100000.00)""",
                ref, contract, account, state);
    }

    @Test
    void findByMandateRefReturnsTheProjectionMappingViewStateOntoState() {
        Optional<MandateProjection> found = dao.findByMandateRef(dao.snapshotTimestamp(), "MND-ACCP");
        assertTrue(found.isPresent(), "the ACCP projection row is found by its mandate_ref");
        assertEquals("MND-ACCP", found.get().mandateRef());
        assertEquals("C-A", found.get().contractRef());
        assertEquals("ACCP", found.get().state(), "man_ctv_view.state maps to MandateProjection.state");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MND-ACCP", "MND-PDNG", "MND-SUSPENDED", "MND-CANC", "MND-RJCT", "MND-EXPIRED"})
    void findByMandateRefResolvesEveryStateByRef(final String ref) {
        Optional<MandateProjection> found = dao.findByMandateRef(dao.snapshotTimestamp(), ref);
        assertTrue(found.isPresent(), ref + " is found by its mandate_ref");
        // The state token is the mandate_ref suffix, so the by-ref lookup returns the right row.
        assertEquals(ref.substring("MND-".length()), found.get().state());
    }

    @Test
    void absentMandateRefYieldsEmpty() {
        assertTrue(dao.findByMandateRef(dao.snapshotTimestamp(), "MND-NONE").isEmpty(),
                "no projection row for an unknown mandate_ref");
    }

    @Test
    void byMandateRefBatchLoadsKeyedByMandateRef() {
        Map<String, MandateProjection> byRef =
                dao.byMandateRef(dao.snapshotTimestamp(), List.of("MND-ACCP", "MND-CANC", "MND-NONE"));
        assertEquals(2, byRef.size(), "only the two present refs are returned");
        assertEquals("ACCP", byRef.get("MND-ACCP").state());
        assertEquals("CANC", byRef.get("MND-CANC").state());
    }

    @Test
    void asOfSnapshotHidesAPostSnapshotProjectionChange() {
        // Snapshot BEFORE the state flip.
        String beforeFlip = dao.snapshotTimestamp();

        // A cancel lands after the snapshot (as the suspension/cancel writer would mid-run).
        manJdbc.update("UPDATE mandate SET state = 'CANC' WHERE mandate_ref = 'MND-FLIP'");

        // The pinned snapshot still sees ACCP: the run is isolated from the change.
        assertEquals("ACCP", dao.findByMandateRef(beforeFlip, "MND-FLIP").orElseThrow().state(),
                "AS OF the pre-change snapshot the mandate is still ACCP");

        // A fresh snapshot does see the cancel (sanity: the write really happened).
        assertEquals("CANC", dao.findByMandateRef(dao.snapshotTimestamp(), "MND-FLIP").orElseThrow().state(),
                "a snapshot taken after the change sees CANC");
    }
}
