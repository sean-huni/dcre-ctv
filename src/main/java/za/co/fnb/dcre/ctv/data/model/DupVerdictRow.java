package za.co.fnb.dcre.ctv.data.model;

/**
 * Read projection of a duplicate verdict joined back to its spine row, used
 * only to emit the R-38 exclusion WARN lines after the set-based dup scan.
 */
public record DupVerdictRow(int sequence, String e2e, String outcome) {
}
