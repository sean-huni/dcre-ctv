package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three CHECK constraints of {@code dcre_col.account}, each driven to REJECT a bad
 * row.
 *
 * <p>They are asserted this way and not by reading {@code information_schema}, for a
 * specific reason recorded in the changeset that creates them: liquibase-core 5.0.3 ships
 * no change type for a CHECK constraint, and the {@code checkConstraint} column attribute
 * that looks like one is a VERIFIED no-op, parsed and never read by
 * {@code CreateTableChange}. A changelog that used it would emit no DDL while appearing to
 * declare three invariants. A constraint present in the catalogue but never driven to fire
 * is coverage claimed by proximity; a constraint that rejects a row is a control.
 *
 * <p>Each test breaks exactly one rule and leaves the rest satisfied, so a green here
 * cannot be one constraint standing in for another.
 *
 * <p><b>The assertions match the constraint EXPRESSION, not its name, and that is a fact
 * about CockroachDB rather than a weakening.</b> Its CHECK violation reads "failed to
 * satisfy CHECK constraint (product_code IN ('FNBRF':::STRING, ...))" and never names the
 * constraint, so an assertion on {@code chk_account_product} would fail even though the
 * right control fired. The three expressions are distinct, so matching them still
 * identifies WHICH constraint rejected the row, which is the property that matters.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange"})
class AccountConstraintsIT {

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
    JdbcTemplate jdbc;

    private static final String INSERT = """
            INSERT INTO account (account_number, product_code, status, app_no, acc_type, branch_code,
                                 balance, max_credit_limit, cancel_reason, country_id, edr_ind,
                                 pre_ind, process_status, status_reason, ucn, client_id)
            VALUES (?,?, 'AAUT','APP-C','CACC','250205', ?, ?, NULL, 1, false, false, 'ACTIVE',
                    NULL, 'UCN-C', 2)""";

    private String rejectionFor(final String number, final String product,
                                final String balance, final String limit) {
        DataIntegrityViolationException raised = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(INSERT, number, product,
                        balance == null ? null : new java.math.BigDecimal(balance),
                        limit == null ? null : new java.math.BigDecimal(limit)));
        return raised.getMessage();
    }

    @Test
    void aRowThatSatisfiesAllThreeIsAccepted() {
        // Control first: without it, every rejection below is equally consistent with a
        // table that refuses every insert this harness attempts.
        jdbc.update(INSERT, "62700000000001", "FNBRF", new java.math.BigDecimal("100.00"), null);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE account_number='62700000000001'", Integer.class));
        jdbc.update("DELETE FROM account WHERE account_number='62700000000001'");
    }

    /** The expression fragment unique to chk_account_product. */
    private static final String PRODUCT_RULE = "product_code IN (";

    /** Unique to chk_account_product_amount: no other CHECK here mentions both amounts together. */
    private static final String PRODUCT_AMOUNT_RULE = "balance IS NOT NULL";

    /** Unique to chk_account_amounts_nonneg. */
    private static final String NONNEG_RULE = ">= 0";

    @Test
    void chkAccountProductRejectsAProductThisFamilyDoesNotCarry() {
        String message = rejectionFor("62700000000002", "FNBXX", "100.00", null);
        assertTrue(message.contains(PRODUCT_RULE),
                "the product-list CHECK must be the one that fired, was: " + message);
    }

    @Test
    void chkAccountProductAmountRejectsABalanceCarryingProductWithNoBalance() {
        String message = rejectionFor("62700000000003", "FNBRF", null, "100.00");
        assertTrue(message.contains(PRODUCT_AMOUNT_RULE) && !message.contains(NONNEG_RULE),
                "the product/amount CHECK, and not the non-negative one, was: " + message);
    }

    @Test
    void chkAccountProductAmountRejectsARowCarryingBothAmounts() {
        String message = rejectionFor("62700000000004", "FNBCC", "100.00", "100.00");
        assertTrue(message.contains(PRODUCT_AMOUNT_RULE) && !message.contains(NONNEG_RULE), message);
    }

    @Test
    void chkAccountAmountsNonnegRejectsANegativeBalance() {
        // FNBRF with a balance and no limit, so the product/amount rule is SATISFIED and
        // only the sign is wrong: one broken rule per test.
        String message = rejectionFor("62700000000005", "FNBRF", "-1.00", null);
        assertTrue(message.contains(NONNEG_RULE) && !message.contains(PRODUCT_AMOUNT_RULE),
                "the non-negative CHECK, and not the product/amount one, was: " + message);
    }

    @Test
    void uqAccountAccountNumberRejectsASecondRowForTheSameAccount() {
        jdbc.update(INSERT, "62700000000006", "FNBRF", new java.math.BigDecimal("100.00"), null);
        String message = rejectionFor("62700000000006", "FNBRF", "200.00", null);
        assertTrue(message.contains("uq_account_account_number") || message.contains("duplicate key"),
                "account_number is the business identity and is UNIQUE, was: " + message);
        jdbc.update("DELETE FROM account WHERE account_number='62700000000006'");
    }

    @Test
    void theNotNullsOfTheCollectionsShapeAreEnforced() {
        // The whole reason each context materialises its OWN projection: the retired shared
        // store had to relax these to a nullable union to hold both shapes.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO account (account_number, product_code, status, acc_type, branch_code,
                                     balance, country_id, edr_ind, pre_ind, process_status, ucn,
                                     client_id)
                VALUES ('62700000000007','FNBRF','AAUT','CACC','250205',100.00,1,false,false,
                        'ACTIVE','UCN-C',2)"""),
                "app_no is NOT NULL in the collections shape and absent here");
    }
}
