package za.co.fnb.dcre.ctv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.data.repo.AccountReferenceNotMaterialisedException;
import za.co.fnb.dcre.ctv.data.repo.ReferenceSnapshotDao;
import za.co.fnb.dcre.ctv.data.repo.ReferenceSnapshotDao.LoadRecord;

/**
 * Runs ONCE per validation run, at the point the as-of snapshot is captured, and answers
 * one question before any verdict is formed: has the account reference ever been
 * materialised into this database?
 *
 * <p><b>Why a run-level guard rather than a check in the chain.</b> The three states below
 * are only distinguishable ONCE, over the whole table. Per transaction they collapse into
 * one another: "no row for this account" is the same empty map whether the table holds a
 * hundred thousand accounts or none at all.
 *
 * <ol>
 *   <li>Artifact absent or invalid: the LOADER halts naming the artifact, and this run
 *       never happens. Not this class's job.</li>
 *   <li>Table populated, no row matches this account: a BUSINESS rejection,
 *       {@code FAIL_ACCOUNT_NOT_FOUND}, formed by {@code VerdictChain}. Untouched here.</li>
 *   <li>Table empty because nothing ever loaded: TECHNICAL, and this class throws. A
 *       deployment step that never happened must not be reported as an arrival's worth of
 *       business rejections.</li>
 * </ol>
 *
 * <p>It also RECORDS what the run consumed. The returned {@code dataset_version} is logged
 * and stored in the job execution context, so "which data did this run use" is answerable
 * from Batch metadata afterwards rather than by inferring it from whatever the table
 * happens to hold at the time somebody asks.
 */
@Component
public class AccountReferenceGuard {

    private static final Logger log = LoggerFactory.getLogger(AccountReferenceGuard.class);

    private final ReferenceSnapshotDao snapshot;

    public AccountReferenceGuard(final ReferenceSnapshotDao snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * @param asOf the run's snapshot timestamp, so the guard describes exactly the table
     *             contents the verdict ranges will read
     * @return the {@code dataset_version} every verdict in this run is judged against
     * @throws AccountReferenceNotMaterialisedException when no load has populated the table
     */
    public String requireMaterialised(final String asOf) {
        LoadRecord load = snapshot.latestLoad(asOf)
                .orElseThrow(AccountReferenceNotMaterialisedException::neverLoaded);
        if (load.appliedRowCount() == 0) {
            // A load DID run, and left nothing behind. Operationally identical to never
            // having run: no transaction can match a row that is not there, so the whole
            // arrival would be rejected for a reason that is not about the input file.
            // Kept as its own message because the REMEDY differs: the step ran, so the
            // artifact is what needs looking at, not the deployment.
            throw AccountReferenceNotMaterialisedException.loadedEmpty(load.datasetVersion());
        }
        log.info("account-reference-materialised stage=CTV dataset={} appliedRows={}",
                load.datasetVersion(), load.appliedRowCount());
        return load.datasetVersion();
    }
}
