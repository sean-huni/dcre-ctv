package za.co.fnb.dcre.ctv.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.ctv.CtvTestTables;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R-41 dup scan: set-based SQL window-function verdicts written BEFORE the
 * per-tx validation pass. First occurrence wins (R-25 in-file scope); the
 * duplicate-e2e rule runs first, so a row that is both an e2e dup and a
 * content dup stays FAIL_DUPLICATE_E2E.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class DupScanServiceIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    DupScanService dupScan;

    @Autowired
    JdbcTemplate jdbc;

    record SeedEntry(int sequence, String e2e, String contentHash) {
    }

    static SeedEntry entry(int sequence, String e2e, String contentHash) {
        return new SeedEntry(sequence, e2e, contentHash);
    }

    @Test
    void laterContentClashFailsFirstOccurrenceWins() {
        UUID arrival = seedArrivalWithEntries(
                entry(1, "E2E-A", "HASH-X"),
                entry(2, "E2E-B", "HASH-X"),   // same content, different e2e
                entry(3, "E2E-C", "HASH-Y"));
        int dups = dupScan.scan(arrival);
        assertEquals(1, dups);
        assertEquals("FAIL_DUPLICATE_TX", outcomeOf(arrival, 2));
        assertNull(outcomeOf(arrival, 1));  // first occurrence untouched
    }

    @Test
    void e2eDupStillDetected() {
        UUID arrival = seedArrivalWithEntries(
                entry(1, "E2E-A", "HASH-1"),
                entry(2, "E2E-A", "HASH-2"));
        dupScan.scan(arrival);
        assertEquals("FAIL_DUPLICATE_E2E", outcomeOf(arrival, 2));
    }

    @Test
    void e2eDupWinsOverContentDupOnSameRow() {
        // precedence: duplicate-e2e rule runs first; a row that is both stays FAIL_DUPLICATE_E2E
        UUID arrival = seedArrivalWithEntries(
                entry(1, "E2E-A", "HASH-1"),
                entry(2, "E2E-A", "HASH-1"));
        dupScan.scan(arrival);
        assertEquals("FAIL_DUPLICATE_E2E", outcomeOf(arrival, 2));
    }

    UUID seedArrivalWithEntries(SeedEntry... entries) {
        CtvTestTables.create(jdbc);
        UUID arrival = UUID.randomUUID();
        for (SeedEntry e : entries) {
            jdbc.update("""
                    INSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account,
                                          contract_ref, amount, content_hash)
                    VALUES (?,?,?,?,NULL,?,?)""",
                    arrival, e.sequence(), e.e2e(), "63999999999901",
                    new BigDecimal("10.00"), e.contentHash());
        }
        return arrival;
    }

    String outcomeOf(UUID arrival, int sequence) {
        List<String> rows = jdbc.queryForList(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, sequence);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
