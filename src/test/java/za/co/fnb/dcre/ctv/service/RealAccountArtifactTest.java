package za.co.fnb.dcre.ctv.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.ctv.config.AccountReferenceProperties;
import za.co.fnb.dcre.ctv.domain.AccountArtifact;
import za.co.fnb.dcre.ctv.domain.AccountReferenceRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses the REAL committed artifact at
 * {@code infra/dcre-infra/fixtures/reference/account/2026.08.09-001/}, verifies its
 * checksum, and asserts what THIS context projects out of it.
 *
 * <p>Every other test in this suite builds its own artifact, and hand-built fixtures on
 * both sides of a seam are a drift class that green tests cannot see: the loader and the
 * fixture would agree with each other forever while agreeing with the published file about
 * nothing. This test is the one that reads the published file.
 *
 * <p>It also pins the CONFIGURED defaults. The root and the dataset version come from the
 * same committed application.yml values a clean clone runs with, so a wrong relative path
 * or a bumped version fails here rather than in a pod.
 */
class RealAccountArtifactTest {

    /** The committed dev defaults from application.yml, deliberately duplicated so a drift shows. */
    private static final String ROOT = "../../../../../../infra/dcre-infra/fixtures/reference/account";
    private static final String DATASET_VERSION = "2026.08.09-001";

    private static final String YML = "src/main/resources/application.yml";

    @Test
    void theCommittedDefaultsInApplicationYmlAreTheOnesAssertedHere() throws Exception {
        // Without this, the constants above could drift from the yml and every assertion
        // below would keep passing about an artifact nothing is configured to read.
        String yml = Files.readString(Path.of(YML));
        assertTrue(yml.contains("DCRE_CTV_ACCOUNT_REFERENCE_ROOT:" + ROOT),
                "application.yml must default the reference root to " + ROOT);
        assertTrue(yml.contains("DCRE_CTV_ACCOUNT_DATASET_VERSION:" + DATASET_VERSION),
                "application.yml must default the dataset version to " + DATASET_VERSION);
        // An UNCOMMENTED max-age line only. The committed file carries a comment saying it
        // is deliberately unset, and asserting on the bare substring would fail on that
        // comment, which is the opposite of what this guards.
        List<String> armed = yml.lines().map(String::strip)
                .filter(line -> line.startsWith("max-age:"))
                .toList();
        assertEquals(List.of(), armed,
                "reference.account.max-age must stay UNSET until A-4 lands; a number here arms a"
                        + " production gate nobody decided");
    }

    @Test
    void theRealArtifactVerifiesAndProjectsExactlyTenCollectionsRows() {
        assertTrue(Files.isDirectory(Path.of(ROOT, DATASET_VERSION)),
                "the committed artifact must be reachable from the service directory at " + ROOT
                        + "/" + DATASET_VERSION + "; a relative path that does not resolve is the"
                        + " defect this test exists to catch");

        AccountArtifact artifact = new AccountArtifactReader(
                new AccountReferenceProperties(ROOT, DATASET_VERSION, null)).read();

        assertEquals(DATASET_VERSION, artifact.manifest().datasetVersion());
        assertEquals(1, artifact.manifest().schemaVersion());
        assertEquals(110, artifact.manifest().rowCount(),
                "the manifest describes the WHOLE artifact: 100 MANDATES + 10 COLLECTIONS");
        assertEquals("5831d612cbcce77f17f5f4ee50dd05cfed14bfc6ce72182649f4eccdb64e75a6",
                artifact.manifest().checksum());
        assertEquals(10, artifact.rows().size(),
                "collections projects the 10 shape=COLLECTIONS rows and NOT the 100 mandates ones");
    }

    @Test
    void everyProjectedRowSatisfiesTheCollectionsNotNullsAndTheProductAmountRule() {
        // The table's own CHECK constraints are asserted against the database in
        // AccountConstraintsIT. This asserts the same invariants hold of the REAL data, so
        // a load of the published artifact cannot be rejected by them.
        List<AccountReferenceRow> rows = new AccountArtifactReader(
                new AccountReferenceProperties(ROOT, DATASET_VERSION, null)).read().rows();
        Set<String> numbers = rows.stream().map(AccountReferenceRow::accountNumber)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(rows.size(), numbers.size(), "account_number is UNIQUE in the target table");

        for (AccountReferenceRow row : rows) {
            assertNotNull(row.appNo(), row.accountNumber());
            assertNotNull(row.accType(), row.accountNumber());
            assertNotNull(row.branchCode(), row.accountNumber());
            assertNotNull(row.processStatus(), row.accountNumber());
            assertNotNull(row.status(), row.accountNumber());
            assertNotNull(row.ucn(), row.accountNumber());
            assertTrue(Set.of("FNBRF", "FNBCC").contains(row.productCode()),
                    "chk_account_product: " + row.productCode());
            if ("FNBRF".equals(row.productCode())) {
                assertNotNull(row.balance(), "chk_account_product_amount: " + row.accountNumber());
                assertNull(row.maxCreditLimit(), "chk_account_product_amount: " + row.accountNumber());
            } else {
                assertNotNull(row.maxCreditLimit(), "chk_account_product_amount: " + row.accountNumber());
                assertNull(row.balance(), "chk_account_product_amount: " + row.accountNumber());
            }
        }
    }
}
