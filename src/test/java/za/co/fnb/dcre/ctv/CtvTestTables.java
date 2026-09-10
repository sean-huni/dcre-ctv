package za.co.fnb.dcre.ctv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared BDD seeding helpers for the CRR-owned spine tables the job tests stand up.
 * {@code validation_log} comes from this service's Liquibase changelog.
 *
 * <p><b>SCRUM-107: {@code account} is NOT created here any more.</b> CTV's own changelog
 * creates it (2026/08/003-ctv-account.xml), in the full 17-column collections shape with
 * its NOT NULLs and its three CHECK constraints, so a helper that also minted a
 * five-column stand-in with {@code IF NOT EXISTS} would be a silent no-op against the real
 * table while looking like the thing under test. Seeding goes through
 * {@link #insertAccount} and must satisfy the real constraints, because in production
 * nothing can put a row in that table which does not.
 */
public final class CtvTestTables {

    /** The DC sample's business date: "today" for mandate window checks. */
    public static final String BUSINESS_DATE = "20260711";

    private CtvTestTables() {
    }

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE,
                    tx_count INT NOT NULL,
                    initg_pty VARCHAR(35) NOT NULL,
                    business_date VARCHAR(8) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL, sequence INT NOT NULL,
                    e2e VARCHAR(35) NOT NULL, creditor_account VARCHAR(23) NOT NULL,
                    contract_ref VARCHAR(14), mandate_ref VARCHAR(35), amount DECIMAL(18,2) NOT NULL,
                    content_hash CHAR(64),
                    UNIQUE (arrival_id, sequence))""");
    }

    /**
     * Seeds one row of the REAL collections shape. The five columns CTV reads are the
     * caller's; the other eleven are fixture values chosen to satisfy the NOT NULLs, which
     * is the point: production cannot hold a row that does not.
     *
     * <p>{@code cap} of {@code "none"} is REJECTED rather than mapped to NULL.
     * {@code chk_account_product_amount} forbids that row outright (FNBRF carries a
     * balance and no limit, FNBCC a limit and no balance), so a test seeding it would be
     * asserting against a state the table cannot reach. The unset-cap arm of the chain is
     * still covered, in {@code VerdictChainAccountTierTest}, which is where it lives and
     * where no table constraint is in the way.
     */
    public static void insertAccount(JdbcTemplate jdbc, String number, String productCode,
                                     String cap, String status) {
        if ("none".equals(cap)) {
            throw new IllegalArgumentException("dcre_col.account cannot hold a row with no cap:"
                    + " chk_account_product_amount requires a balance for FNBRF and a limit for"
                    + " FNBCC. Assert the unset-cap arm in VerdictChainAccountTierTest instead.");
        }
        BigDecimal capValue = new BigDecimal(cap);
        boolean balanceCarrying = productCode.startsWith("FNBRF");
        jdbc.update("""
                INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES (?,?,'AAUT',?,'CACC','250205',?,?,NULL,1,false,false,?,NULL,?,2)""",
                number, productCode, "APP-" + number,
                balanceCarrying ? capValue : null,
                balanceCarrying ? null : capValue,
                // ucn is VARCHAR(20) and the account number fits: no prefix, which would
                // overflow it for a 17-digit number and fail for a reason unrelated to
                // whatever the test is actually about.
                status, number);
    }

    /** The dataset version every fixture load claims. Asserted by AccountReferenceGuardIT. */
    public static final String FIXTURE_DATASET_VERSION = "2026.08.09-001";

    /**
     * An account no scenario ever collects against. It stands for the REST of the artifact:
     * a real load applies every COLLECTIONS row, while these fixtures seed only the two or
     * three accounts a scenario is about. Without it a scenario that deliberately seeds no
     * account (the "account absent from the store" case) would describe a database with a
     * load record over an empty table, which is a state production cannot reach.
     */
    public static final String SENTINEL_ACCOUNT = "63000000000000";

    /**
     * Puts the fixture database into the state a DEPLOYED one is in: the account reference
     * has been materialised, and {@code account_reference_load} records it. Every test that
     * runs {@code ctvJob} must call this immediately before starting the job.
     *
     * <p>Without it the run fails the account-reference guard, and that is not an
     * inconvenience to be worked around: it is the guard doing its job. Before this wave
     * these suites ran against an empty {@code account} table and got
     * {@code FAIL_ACCOUNT_NOT_FOUND} verdicts that looked like findings about the input.
     *
     * <p>Idempotent: the load record is REPLACED, so the recorded count always matches what
     * the table actually holds at the moment the job starts. A stale record claiming a
     * different number would be a second place holding one fact.
     */
    public static void materialiseAccountReference(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES (?,'FNBRF','AAUT','APP-SENTINEL','CACC','250205',1.00,NULL,NULL,1,
                        false,false,'ACTIVE',NULL,?,2)
                ON CONFLICT (account_number) DO NOTHING""",
                SENTINEL_ACCOUNT, SENTINEL_ACCOUNT);
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        jdbc.update("DELETE FROM account_reference_load");
        jdbc.update("""
                INSERT INTO account_reference_load (dataset_version, schema_version, source_id,
                    effective_ts, publication_ts, row_count, checksum, applied_row_count)
                VALUES (?, 1, 'fixture:CtvTestTables', '2026-08-09T00:00:00Z',
                        '2026-08-09T00:00:00Z', ?, 'fixture', ?)""",
                FIXTURE_DATASET_VERSION, applied, applied);
    }

    public static void insertHeader(JdbcTemplate jdbc, UUID arrival, int txCount) {
        insertHeader(jdbc, arrival, txCount, "FNBCC01");
    }

    public static void insertHeader(JdbcTemplate jdbc, UUID arrival, int txCount, String initgPty) {
        jdbc.update("INSERT INTO tx_header (arrival_id, tx_count, initg_pty, business_date) VALUES (?,?,?,?)",
                arrival, txCount, initgPty, BUSINESS_DATE);
    }

    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount) {
        insertEntry(jdbc, arrival, sequence, e2e, account, contract, amount, null);
    }

    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount, String contentHash) {
        insertEntry(jdbc, arrival, sequence, e2e, account, contract, amount, contentHash, null);
    }

    /**
     * SCRUM-107 (review I3): {@code mandateRef} overload. Without it every entry this
     * harness writes carries a NULL mandate_ref, which makes the projection gate a
     * documented no-op, so no suite built on it can exercise the mandate tier at all.
     */
    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount,
                                   String contentHash, String mandateRef) {
        jdbc.update("""
                INSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref,
                                      mandate_ref, amount, content_hash)
                VALUES (?,?,?,?,?,?,?,?)""",
                arrival, sequence, e2e, account, contract, mandateRef,
                new BigDecimal(amount), contentHash);
    }
}
