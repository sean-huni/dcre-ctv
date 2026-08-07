package za.co.fnb.dcre.ctv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Shared BDD seeding helpers: the same minimal AIS-shaped read models and
 * CRR-owned spine tables the existing job tests stand up (CtvEndoModeTest).
 * validation_log itself comes from this service's Liquibase changelog.
 */
public final class CtvTestTables {

    /** The DC sample's business date: "today" for mandate window checks. */
    public static final String BUSINESS_DATE = "20260711";

    private CtvTestTables() {
    }

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    account_number VARCHAR(34) NOT NULL UNIQUE,
                    product_code VARCHAR(8) NOT NULL,
                    balance DECIMAL(18,2) NULL,
                    max_credit_limit DECIMAL(18,2) NULL,
                    process_status VARCHAR(16) NOT NULL)""");
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

    public static void insertAccount(JdbcTemplate jdbc, String number, String productCode,
                                     String cap, String status) {
        BigDecimal capValue = "none".equals(cap) ? null : new BigDecimal(cap);
        boolean balanceCarrying = productCode.startsWith("FNBRF");
        jdbc.update("""
                INSERT INTO account (account_number, product_code, balance, max_credit_limit, process_status)
                VALUES (?,?,?,?,?)""",
                number, productCode,
                balanceCarrying ? capValue : null,
                balanceCarrying ? null : capValue,
                status);
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
