package za.co.fnb.dcre.ctv.domain;

import java.util.Locale;

/**
 * SCRUM-107: which store backs the DC-flow mandate gate. There is now exactly ONE,
 * {@link #PROJECTION}: the mandates-owned {@code dcre_man} projection, read through
 * the grants-based {@code man_ctv_view} contract view (R-10). The projection state
 * IS the gate: only {@code ACCP} is collectable.
 *
 * <p>The former {@code LEGACY} value read {@code dcre_col.mandate}, which was dropped
 * in {@code 2026/08/001-drop-local-mandate.xml}. {@link #from(String)} therefore FAILS
 * CLOSED on it rather than silently falling back: a pod still carrying
 * {@code DCRE_CTV_MANDATE_SOURCE=legacy} from before the cleanout must refuse to start
 * with a message that names the cause, because the alternative is a gate that queries a
 * table that no longer exists and fails per-arrival, deep inside a batch run.
 */
public enum MandateSource {

    PROJECTION;

    public static MandateSource from(final String value) {
        if (value == null || value.isBlank()) {
            return PROJECTION;
        }
        final String normalised = value.strip().toUpperCase(Locale.ROOT);
        if ("LEGACY".equals(normalised)) {
            throw new IllegalArgumentException(
                    "dcre.ctv.mandate-source=legacy is no longer supported (SCRUM-107): the"
                            + " dcre_col.mandate table it read was dropped. The mandate gate reads"
                            + " dcre_man.man_ctv_view. Unset DCRE_CTV_MANDATE_SOURCE or set it to"
                            + " 'projection'.");
        }
        return valueOf(normalised);
    }
}
