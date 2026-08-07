package za.co.fnb.dcre.ctv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;

/**
 * SCRUM-107 (review C1): the dcre_man projection store, stood up inside a test's OWN
 * CockroachDB container.
 *
 * <p>Why this exists. {@code MandatesDatasourceConfig} is {@code @Import}-ed on
 * {@code CtvApplication}, so the mandates datasource is in EVERY {@code @SpringBootTest}
 * context, and its committed default URL is {@code localhost:26257}. Once the mandate
 * gate stopped being optional, {@code MandateGate.snapshot} opened that connection on
 * every DC arrival. Only {@code CtvProjectionGateIT} declared the URL, so every other
 * job-running suite silently resolved {@code localhost:26257} and passed only because
 * an unrelated CockroachDB container happened to be publishing that port on the build
 * machine. CockroachDB answers {@code cluster_logical_timestamp()} even for a database
 * that does not exist, so the borrowed server never complained. On a clean clone the
 * suite throws.
 *
 * <p>The rule this restores: a test declares every backing service it touches, against a
 * container it owns. Every one of the 9 job-running contexts now does so.
 *
 * <p>NOT YET ENFORCED, and that gap is deliberate rather than overlooked (review C1c).
 * The enforcement is one line on the Gradle test task:
 * {@code systemProperty 'dcre.ctv.mandates-db-url', CLOSED_PORT_URL}. A system property
 * outranks application.yml and is outranked by {@code @DynamicPropertySource}, so every
 * suite that declares its own container still wins while a new one that forgets gets a
 * closed port and dies instead of borrowing. It is not applied here because build.gradle
 * is being edited concurrently by the platform-copybook extraction and this change may
 * not carry that unrelated work into its commit. A JUnit LauncherSessionListener was
 * tried as a build-file-free substitute and does not compile: junit-platform-launcher is
 * not on the test COMPILE classpath, so it needs a build.gradle dependency too.
 * MandatesDatasourceConfigTest is already insulated, so the line can be added on its own.
 *
 * <p>Call {@link #create(CockroachContainer)} from the same static block that starts the
 * container, so the database exists before any Spring context resolves the URL.
 */
public final class ManProjectionFixture {

    /** A port nothing listens on: the ENDO contexts point here deliberately. */
    public static final String CLOSED_PORT_URL = "jdbc:postgresql://127.0.0.1:1/dcre_man?sslmode=disable";

    private ManProjectionFixture() {
    }

    public static String url(final CockroachContainer crdb) {
        return "jdbc:postgresql://" + crdb.getHost() + ":" + crdb.getMappedPort(26257)
                + "/dcre_man?sslmode=disable";
    }

    /**
     * Creates the dcre_man database and the published {@code man_ctv_view} contract.
     * {@code start_date}/{@code expiry_date} are VARCHAR(8) CCYYMMDD, matching the real
     * mrg contract: declaring them DATE is the fixture defect that once hid a live
     * {@code rs.getDate} crash behind a green suite.
     */
    public static void create(final CockroachContainer crdb) {
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword()));
        root.execute("CREATE DATABASE IF NOT EXISTS dcre_man");

        JdbcTemplate man = jdbc(crdb);
        man.execute("""
                CREATE TABLE IF NOT EXISTS mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL, contract_ref VARCHAR(35) NOT NULL,
                    creditor_account VARCHAR(34) NOT NULL, state VARCHAR(16) NOT NULL,
                    start_date VARCHAR(8) NULL, expiry_date VARCHAR(8) NULL,
                    max_collection_amount DECIMAL(18,2) NULL)""");
        man.execute("""
                CREATE VIEW IF NOT EXISTS man_ctv_view AS
                    SELECT mandate_ref, contract_ref, creditor_account, state, start_date,
                           expiry_date, max_collection_amount FROM mandate""");
    }

    public static JdbcTemplate jdbc(final CockroachContainer crdb) {
        return new JdbcTemplate(new DriverManagerDataSource(
                url(crdb), crdb.getUsername(), crdb.getPassword()));
    }

    /** Seeds one projection row in the given state. */
    public static void seed(final CockroachContainer crdb, final String mandateRef,
                            final String contractRef, final String creditorAccount,
                            final String state) {
        jdbc(crdb).update("""
                INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state,
                                     start_date, expiry_date, max_collection_amount)
                VALUES (?,?,?,?,'20260101',NULL,NULL)""",
                mandateRef, contractRef, creditorAccount, state);
    }
}
