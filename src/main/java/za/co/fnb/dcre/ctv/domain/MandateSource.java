package za.co.fnb.dcre.ctv.domain;

import java.util.Locale;

/**
 * M10 T15 (SCRUM-78): which store backs the DC-flow mandate gate.
 *
 * <ul>
 *   <li>{@link #LEGACY} - the collections-side dcre_col {@code mandate} table
 *       (the current status/effective/expiry/cap chain). Stays the default and
 *       the sole reader until the M11 legacy-mandate retirement (ruling note 1).</li>
 *   <li>{@link #PROJECTION} - the MSR-owned dcre_man projection, read through the
 *       grants-based {@code man_ctv_view} contract view (R-10). The projection
 *       state IS the gate: only {@code ACCP} is collectable.</li>
 * </ul>
 *
 * <p>The {@code dcre.ctv.mandate-source} property (values {@code legacy|projection})
 * flips it; the T16 gate env sets {@code projection} once the toolkit has seeded
 * dcre_man. Parsed via {@link #from(String)} so the lowercase property value binds
 * regardless of the ambient {@code @Value} conversion service.
 */
public enum MandateSource {

    LEGACY,
    PROJECTION;

    public static MandateSource from(final String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
