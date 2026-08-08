package za.co.fnb.dcre.ctv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fnb.dcre.ctv.data.repo.AccountReferenceDao;
import za.co.fnb.dcre.ctv.domain.AccountArtifact;

/**
 * Business tier (configuration.md point 21) of the account reference load: read the
 * verified artifact, then apply it atomically.
 *
 * <p>The read happens OUTSIDE the transaction and the write inside it, which is the right
 * way round: every artifact defect is detected before a single row of the live table is
 * touched, so the overwhelmingly common failure mode never opens a transaction at all.
 *
 * <p>{@link Propagation#REQUIRED} rather than {@code REQUIRES_NEW}: when the Batch step
 * calls this, its step transaction IS the one transaction the contract demands, and the
 * load record must share it with the data. A new transaction here would commit the rows
 * independently of the step's own bookkeeping and reintroduce exactly the split this
 * design removes.
 */
@Service
public class AccountReferenceLoadService {

    private static final Logger log = LoggerFactory.getLogger(AccountReferenceLoadService.class);

    private final AccountArtifactReader reader;
    private final AccountReferenceDao accounts;

    public AccountReferenceLoadService(final AccountArtifactReader reader,
                                       final AccountReferenceDao accounts) {
        this.reader = reader;
        this.accounts = accounts;
    }

    /**
     * Reads, verifies and applies. Returns the number of rows materialised into
     * {@code dcre_col.account}, which is this context's projection and NOT the artifact's
     * whole row count.
     *
     * @param jobExecutionId the Batch execution, or null when called outside one
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int load(final Long jobExecutionId) {
        AccountArtifact artifact = reader.read();
        accounts.replaceAll(artifact.rows());
        accounts.recordLoad(artifact.manifest(), artifact.rows().size(), jobExecutionId);
        log.info("account-reference load applied dataset={} schemaVersion={} artifactRows={}"
                        + " appliedRows={} checksum={}",
                artifact.manifest().datasetVersion(), artifact.manifest().schemaVersion(),
                artifact.manifest().rowCount(), artifact.rows().size(),
                artifact.manifest().checksum());
        return artifact.rows().size();
    }
}
