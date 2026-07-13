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
                CREATE TABLE IF NOT EXISTS mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    start_date DATE NOT NULL,
                    expiry_date DATE NULL,
                    max_collection_amount DECIMAL(18,2) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE,
                    tx_count INT NOT NULL,
                    business_date VARCHAR(8) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL, sequence INT NOT NULL,
                    e2e VARCHAR(35) NOT NULL, creditor_account VARCHAR(23) NOT NULL,
                    contract_ref VARCHAR(14), amount DECIMAL(18,2) NOT NULL,
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

    public static void insertMandate(JdbcTemplate jdbc, String account, String contract,
                                     String status, String start, String expiry, String maximum) {
        jdbc.update("""
                INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, status,
                                     start_date, expiry_date, max_collection_amount)
                VALUES (?,?,?,?,?,?,?)""",
                "MND-" + UUID.randomUUID().toString().substring(0, 8),
                contract, account, status,
                Date.valueOf(LocalDate.parse(start)),
                "none".equals(expiry) || expiry == null ? null : Date.valueOf(LocalDate.parse(expiry)),
                new BigDecimal(maximum));
    }

    public static void insertHeader(JdbcTemplate jdbc, UUID arrival, int txCount) {
        jdbc.update("INSERT INTO tx_header (arrival_id, tx_count, business_date) VALUES (?,?,?)",
                arrival, txCount, BUSINESS_DATE);
    }

    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount) {
        jdbc.update("""
                INSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref, amount)
                VALUES (?,?,?,?,?,?)""",
                arrival, sequence, e2e, account, contract, new BigDecimal(amount));
    }
}
