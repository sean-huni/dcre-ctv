package za.co.fnb.dcre.ctv.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.data.repo.MandateProjectionDao;
import za.co.fnb.dcre.ctv.data.repo.ReferenceSnapshotDao;
import za.co.fnb.dcre.ctv.domain.MandateSource;
import za.co.fnb.dcre.ctv.service.VerdictChain.Mandate;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * M10 T15 (SCRUM-78, Sean directive: key on MANDATE_REF): encapsulates WHICH
 * mandate store backs the DC-flow gate, keeping the {@code legacy|projection}
 * choice out of {@link ValidationService}.
 *
 * <ul>
 *   <li>{@code LEGACY} - reads the dcre_col {@code mandate} table via
 *       {@link ReferenceSnapshotDao} (matched by contract_ref), at the SAME
 *       collections snapshot the account tier uses (the F51 single-snapshot
 *       invariant is preserved for legacy).</li>
 *   <li>{@code PROJECTION} - reads dcre_man {@code man_ctv_view} via
 *       {@link MandateProjectionDao} by mandate_ref, at that store's OWN as-of
 *       snapshot.</li>
 * </ul>
 *
 * Each accessor returns an empty map in the non-active mode, so the caller is
 * branch-free and the inactive store's connection is never opened: in legacy mode
 * dcre_man is never contacted, and vice versa.
 */
@Component
public class MandateGate {

    private final MandateSource source;
    private final ReferenceSnapshotDao legacy;
    private final MandateProjectionDao projection;

    public MandateGate(@Value("${dcre.ctv.mandate-source:legacy}") final String source,
                       final ReferenceSnapshotDao legacy, final MandateProjectionDao projection) {
        this.source = MandateSource.from(source);
        this.legacy = legacy;
        this.projection = projection;
    }

    public MandateSource source() {
        return source;
    }

    /**
     * The mandate as-of timestamp to pin the range reads to. Projection mode
     * captures the dcre_man HLC; legacy mode returns empty (the legacy mandate read
     * reuses the collections snapshot already captured for the account tier).
     */
    public String snapshot() {
        return source == MandateSource.PROJECTION ? projection.snapshotTimestamp() : "";
    }

    /**
     * Legacy dcre_col mandate rows for the given accounts, AS OF the collections
     * snapshot. Empty (and untouched) in projection mode.
     */
    public Map<String, List<Mandate>> legacyMandatesByAccount(final String colAsOf,
                                                              final Collection<String> accountNumbers) {
        return source == MandateSource.LEGACY
                ? legacy.mandatesByAccount(colAsOf, accountNumbers)
                : Map.of();
    }

    /**
     * Projection rows keyed by mandate_ref, read from man_ctv_view AS OF the
     * dcre_man snapshot. Empty (and untouched) in legacy mode.
     */
    public Map<String, MandateProjection> projectionByRef(final String mandateAsOf,
                                                          final Collection<String> mandateRefs) {
        return source == MandateSource.PROJECTION
                ? projection.byMandateRef(mandateAsOf, mandateRefs)
                : Map.of();
    }
}
