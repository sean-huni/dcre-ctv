package za.co.fnb.dcre.ctv.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.data.repo.MandateProjectionDao;
import za.co.fnb.dcre.ctv.domain.MandateSource;
import za.co.fnb.dcre.ctv.service.VerdictChain.MandateProjection;

import java.util.Collection;
import java.util.Map;

/**
 * SCRUM-107: encapsulates the store backing the DC-flow gate, keeping the read
 * out of {@link ValidationService}. There is one store: the mandates-owned
 * {@code dcre_man man_ctv_view}, read by mandate_ref at that store's OWN as-of
 * snapshot via {@link MandateProjectionDao}.
 *
 * <p>The property is still bound and parsed, so a pod carrying the retired
 * {@code legacy} value fails closed at bean creation with a message that names the
 * cause (see {@link MandateSource#from(String)}) instead of failing per-arrival
 * against a table that no longer exists.
 */
@Component
public class MandateGate {

    private final MandateSource source;
    private final MandateProjectionDao projection;

    public MandateGate(@Value("${dcre.ctv.mandate-source:projection}") final String source,
                       final MandateProjectionDao projection) {
        this.source = MandateSource.from(source);
        this.projection = projection;
    }

    public MandateSource source() {
        return source;
    }

    /**
     * The dcre_man HLC to pin the projection range read to, or {@code ""} on the ENDO
     * flow, which has no mandate gate at all (R-20).
     *
     * <p>The {@code dcFlow} guard is a FAILURE-DOMAIN boundary, not an optimisation.
     * {@code VerdictChain.classify} returns PASS for an ENDO entry before it looks at
     * the projection, so an unguarded snapshot would open a dcre_man connection, on
     * every ENDO arrival, for a timestamp nothing then reads. That would make
     * collections-ENDO unavailable whenever the mandates database is unreachable, and
     * a context must not take an outage for a store it does not consult.
     */
    public String snapshot(final boolean dcFlow) {
        return dcFlow ? projection.snapshotTimestamp() : "";
    }

    /**
     * Projection rows keyed by mandate_ref, read from man_ctv_view AS OF the
     * dcre_man snapshot.
     */
    public Map<String, MandateProjection> projectionByRef(final String mandateAsOf,
                                                          final Collection<String> mandateRefs) {
        return projection.byMandateRef(mandateAsOf, mandateRefs);
    }
}
