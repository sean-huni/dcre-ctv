package za.co.fnb.dcre.ctv.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.ctv.config.AccountReferenceProperties;
import za.co.fnb.dcre.ctv.domain.AccountArtifact;
import za.co.fnb.dcre.ctv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.ctv.domain.AccountReferenceRow;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The read half of the loader, in the order the contract fixes: resolve the directory,
 * read and match the manifest, apply the freshness gate, verify the bytes, parse them and
 * project this context's rows. Nothing here touches the database; the write half is
 * {@link AccountReferenceLoadService}, and the split is what lets every failure path be
 * red-proofed without a container.
 *
 * <p>The order matters and is not incidental. The freshness gate runs BEFORE the bytes are
 * hashed so that, once A-4 arms it, a stale artifact fails on being stale rather than on
 * whatever the parser happens to notice first.
 */
@Service
public class AccountArtifactReader {

    private final AccountReferenceProperties properties;

    public AccountArtifactReader(final AccountReferenceProperties properties) {
        this.properties = properties;
    }

    public AccountArtifact read() {
        return read(Instant.now());
    }

    /** Clock injected so the freshness gate is testable without waiting for one to elapse. */
    public AccountArtifact read(final Instant now) {
        Path directory = ReferenceManifestReader.resolveDirectory(
                properties.root(), properties.datasetVersion());
        AccountReferenceManifest manifest =
                ReferenceManifestReader.read(directory, properties.datasetVersion());
        ReferenceManifestReader.checkFreshness(manifest, properties.maxAge(), now);
        List<AccountReferenceRow> rows = AccountCsvReader.read(directory, manifest);
        return new AccountArtifact(manifest, rows);
    }
}
