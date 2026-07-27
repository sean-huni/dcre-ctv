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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** The CCYYMMDD start_date the live dcre_man cluster actually carries. */
    static final String LIVE_START = "20260726";

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
        // The man-core mandate table shape (state column) + the T15 view.
        //
        // start_date/expiry_date are VARCHAR(8) CCYYMMDD, NOT DATE. That is the real
        // contract: mandate_request_entry (mrr 001-man-spine) declares both VARCHAR(8),
        // mnd_ext_status (mrg 004) projects them through unchanged ("projected as the
        // VARCHAR(8) CCYYMMDD the spine stores"), and mandate_effective_status (mrg 005)
        // and mandate_current_status (mrg 007) only ever apply to_date(..., 'YYYYMMDD')
        // to them inside a predicate. man_ctv_view therefore hands CTV strings.
        //
        // This fixture declared DATE until 2026-07-27, which is precisely why 100 green
        // tests never caught rs.getDate() blowing up with ArrayIndexOutOfBoundsException
        // against a live projection row. A fixture that redeclares a production table
        // with different column types tests the fixture, not the contract.
        manJdbc.execute("""
                CREATE TABLE mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(35) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    start_date VARCHAR(8) NULL,
                    expiry_date VARCHAR(8) NULL,
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
        // The three shapes the projection really carries, all of which the old DATE
        // fixture made unrepresentable.
        insertDates("MND-BLANK-EXPIRY", "ACCP", LIVE_START, "");      // MRR's "no expiry"
        insertDates("MND-NULL-DATES", "ACCP", null, null);            // the live cluster row
        insertDates("MND-PADDED-EXPIRY", "ACCP", LIVE_START, "   ");  // COBOL space padding
        insertDates("MND-BAD-EXPIRY", "ACCP", LIVE_START, "20261301");// month 13: not a date
        dao = new MandateProjectionDao(manJdbc);
    }

    static void insert(String ref, String contract, String account, String state) {
        insertDates(ref, state, LIVE_START, "20271231", contract, account);
    }

    static void insertDates(String ref, String state, String start, String expiry) {
        insertDates(ref, state, start, expiry, "C-Z", "ZZZ");
    }

    static void insertDates(String ref, String state, String start, String expiry,
                            String contract, String account) {
        manJdbc.update("""
                INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state,
                                     start_date, expiry_date, max_collection_amount)
                VALUES (?,?,?,?,?,?, 100000.00)""",
                ref, contract, account, state, start, expiry);
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
    void ccyymmddStringsMapOntoLocalDates() {
        MandateProjection found = dao.findByMandateRef(dao.snapshotTimestamp(), "MND-ACCP").orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 26), found.startDate(),
                "the VARCHAR(8) CCYYMMDD start_date parses as a LocalDate");
        assertEquals(LocalDate.of(2027, 12, 31), found.expiryDate(),
                "the VARCHAR(8) CCYYMMDD expiry_date parses as a LocalDate");
    }

    @Test
    void aBlankExpiryIsNoExpiry() {
        // MRR writes an empty string for "no expiry"; mrg 005/007 guard it with <> ''.
        MandateProjection found =
                dao.findByMandateRef(dao.snapshotTimestamp(), "MND-BLANK-EXPIRY").orElseThrow();
        assertNull(found.expiryDate(), "an empty-string expiry_date reads as no expiry");
        assertEquals(LocalDate.of(2026, 7, 26), found.startDate(), "the start date is unaffected");
    }

    @Test
    void aSpacePaddedExpiryIsAlsoNoExpiry() {
        // COBOL fixed-width fields arrive space padded; blank is blank either way.
        assertNull(dao.findByMandateRef(dao.snapshotTimestamp(), "MND-PADDED-EXPIRY")
                        .orElseThrow().expiryDate(),
                "a space-padded expiry_date reads as no expiry");
    }

    @Test
    void nullDatesStayNull() {
        // Exactly the row shape the live cluster carries today (expiry_date NULL).
        MandateProjection found =
                dao.findByMandateRef(dao.snapshotTimestamp(), "MND-NULL-DATES").orElseThrow();
        assertNull(found.startDate(), "a NULL start_date stays null");
        assertNull(found.expiryDate(), "a NULL expiry_date stays null");
    }

    @Test
    void aMalformedNonBlankDateFailsLoudlyNamingTheRow() {
        // Deliberate: silently nulling would let an admissibility gate decide against a
        // mandate whose window is unknown. A bad CCYYMMDD means the spine holds garbage.
        String asOf = dao.snapshotTimestamp();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> dao.findByMandateRef(asOf, "MND-BAD-EXPIRY"));
        assertTrue(thrown.getMessage().contains("expiry_date"), "the failing column is named");
        assertTrue(thrown.getMessage().contains("20261301"), "the offending value is quoted");
        assertTrue(thrown.getMessage().contains("MND-BAD-EXPIRY"), "the offending row is named");
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
