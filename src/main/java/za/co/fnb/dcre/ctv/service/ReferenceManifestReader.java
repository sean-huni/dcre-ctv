package za.co.fnb.dcre.ctv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import za.co.fnb.dcre.ctv.domain.AccountArtifactException;
import za.co.fnb.dcre.ctv.domain.AccountReferenceManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/**
 * Steps 1 to 4 and 7 of the loader contract: resolve the artifact directory, read the
 * manifest, check both declared versions against what this service expects, and apply the
 * freshness gate.
 *
 * <p>Every branch here FAILS rather than degrading. In particular an absent directory is
 * never "nothing to load": it means the loader could not look, and a loader that cannot
 * look must not leave the table holding whatever it held before while reporting success.
 */
public final class ReferenceManifestReader {

    static final String MANIFEST_FILE = "manifest.properties";

    private static final Logger log = LoggerFactory.getLogger(ReferenceManifestReader.class);

    private ReferenceManifestReader() {
    }

    /** Step 1: the versioned directory, which must exist and be a readable directory. */
    public static Path resolveDirectory(final String root, final String datasetVersion) {
        if (root == null || root.isBlank() || datasetVersion == null || datasetVersion.isBlank()) {
            throw new AccountArtifactException("set dcre.ctv.reference.account.root and"
                    + " .dataset-version: root='" + root + "' dataset-version='" + datasetVersion + "'");
        }
        Path directory = Path.of(root, datasetVersion);
        if (!Files.isDirectory(directory)) {
            throw new AccountArtifactException("account reference artifact directory is absent or is"
                    + " not a directory: " + directory.toAbsolutePath()
                    + "; the load FAILS rather than continuing with whatever is already in the table");
        }
        return directory;
    }

    /** Steps 2 to 4: parse all seven keys, then match both declared versions. */
    public static AccountReferenceManifest read(final Path directory, final String expectedDatasetVersion) {
        Path manifestFile = directory.resolve(MANIFEST_FILE);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(manifestFile)) {
            properties.load(in);
        } catch (IOException e) {
            throw new AccountArtifactException(
                    "cannot read " + manifestFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        AccountReferenceManifest manifest = AccountReferenceManifest.from(properties);
        if (!expectedDatasetVersion.equals(manifest.datasetVersion())) {
            throw new AccountArtifactException("dataset.version mismatch: this service expects '"
                    + expectedDatasetVersion + "' and the manifest at " + manifestFile.toAbsolutePath()
                    + " declares '" + manifest.datasetVersion() + "'");
        }
        if (manifest.schemaVersion() != AccountReferenceManifest.SUPPORTED_SCHEMA_VERSION) {
            throw new AccountArtifactException("schema.version mismatch: this loader supports "
                    + AccountReferenceManifest.SUPPORTED_SCHEMA_VERSION + " and the manifest declares "
                    + manifest.schemaVersion());
        }
        return manifest;
    }

    /**
     * Step 7, the max-age gate: wired and INERT until A-4 supplies a number. Unset logs at
     * INFO on EVERY run and proceeds, so an operator reading a normal log can see that
     * freshness is not being enforced; silence would be indistinguishable from a check
     * that ran and passed.
     */
    public static void checkFreshness(final AccountReferenceManifest manifest, final Duration maxAge,
                                      final Instant now) {
        if (maxAge == null) {
            log.info("account-reference freshness check INERT: dcre.ctv.reference.account.max-age is"
                    + " unset pending A-4, so publication.ts={} was NOT checked against any limit;"
                    + " proceeding with dataset {}", manifest.publicationTs(), manifest.datasetVersion());
            return;
        }
        Duration age = Duration.between(manifest.publicationTs(), now);
        if (age.compareTo(maxAge) > 0) {
            throw new AccountArtifactException("account reference artifact is stale: dataset "
                    + manifest.datasetVersion() + " was published at " + manifest.publicationTs()
                    + ", age " + age + " exceeds dcre.ctv.reference.account.max-age " + maxAge);
        }
    }
}
