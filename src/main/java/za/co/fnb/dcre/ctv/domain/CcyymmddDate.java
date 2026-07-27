package za.co.fnb.dcre.ctv.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The COBOL {@code CCYYMMDD} date form the mandate spine stores, and every derived
 * dcre_man view carries through unchanged.
 *
 * <p>{@code mandate_request_entry.start_date}/{@code expiry_date} are declared
 * {@code VARCHAR(8)} (mrr {@code 001-man-spine.xml}); {@code mnd_ext_status} (mrg 004)
 * projects them verbatim ("projected as the VARCHAR(8) CCYYMMDD the spine stores"), and
 * {@code mandate_effective_status} (mrg 005) / {@code mandate_current_status} (mrg 007)
 * only ever apply {@code to_date(..., 'YYYYMMDD')} to them INSIDE a predicate. So
 * {@code man_ctv_view} publishes strings, and a JDBC {@code getDate} on those columns
 * blows up in pgjdbc's {@code TimestampUtils.parseDate}, which expects {@code YYYY-MM-DD}
 * and reads index 8 of an 8-character string.
 *
 * <p>Blank is absence, not a bad value: MRR writes an empty string for "no expiry" (the
 * reason mrg 005/007 guard their expiry arms with {@code <> ''}), and a COBOL fixed-width
 * field can arrive space padded, so both collapse to {@code null}.
 *
 * <p>A non-blank value that is not a CCYYMMDD date FAILS LOUDLY rather than degrading to
 * {@code null}. Nulling it would publish "this mandate has no window" on the strength of
 * garbage, and CTV is an admissibility gate: a silently unbounded window is a fail-open on
 * a date-bounded authority. A malformed value here means the spine itself holds a bad
 * date, which is a data-integrity defect that must surface at the exact row rather than be
 * absorbed. This is also what {@code ValidationService} already does with the equally
 * {@code VARCHAR(8)} {@code tx_header.business_date}.
 */
public final class CcyymmddDate {

    private CcyymmddDate() {
    }

    /**
     * The {@code LocalDate} for a raw CCYYMMDD column value, or {@code null} when the
     * column is SQL NULL, empty or blank.
     *
     * @param column the column name, for the failure message only
     * @param ref    the owning {@code mandate_ref}, for the failure message only
     * @param raw    the column value exactly as the driver returned it
     * @throws IllegalStateException when a non-blank value is not a CCYYMMDD date
     */
    public static LocalDate parse(final String column, final String ref, final String raw) {
        if (raw == null) {
            return null;
        }
        final String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "malformed CCYYMMDD %s '%s' on mandate_ref %s".formatted(column, value, ref), e);
        }
    }
}
