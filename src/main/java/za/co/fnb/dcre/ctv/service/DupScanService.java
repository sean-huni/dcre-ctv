package za.co.fnb.dcre.ctv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.ctv.data.model.DupVerdictRow;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogRepo;

import java.util.UUID;

/**
 * R-41 duplicate scan: one sequential set-based pass per arrival, BEFORE the
 * per-tx validation phase. Two SQL window-function inserts, in precedence
 * order: duplicate e2e (R-25 in-file scope) first, then content-hash clash
 * (FAIL_DUPLICATE_TX). Both use ON CONFLICT DO NOTHING keyed
 * (arrival_id, sequence), which encodes both first-occurrence-wins and the
 * e2e-over-content precedence on a row that is both.
 */
@Service
public class DupScanService {

    private static final Logger log = LoggerFactory.getLogger(DupScanService.class);

    private final ValidationLogRepo verdicts;

    public DupScanService(ValidationLogRepo verdicts) {
        this.verdicts = verdicts;
    }

    /** Writes dup verdicts for the arrival; returns the number written. */
    public int scan(UUID arrivalId) {
        int e2eDups = verdicts.insertE2eDupVerdicts(arrivalId);
        int contentDups = verdicts.insertContentDupVerdicts(arrivalId);
        int total = e2eDups + contentDups;
        if (total > 0) {
            // R-38 exclusion visibility: WARN at decision time; validation_log
            // remains the durable record.
            for (DupVerdictRow row : verdicts.findDupVerdictRows(arrivalId)) {
                log.warn("excluded stage=CTV arrival={} seq={} e2e={} reason=CTV_{}",
                        arrivalId, row.sequence(), row.e2e(), row.outcome());
            }
        }
        return total;
    }
}
