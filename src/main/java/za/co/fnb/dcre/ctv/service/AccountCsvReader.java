package za.co.fnb.dcre.ctv.service;

import za.co.fnb.dcre.ctv.domain.AccountArtifactException;
import za.co.fnb.dcre.ctv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.ctv.domain.AccountReferenceRow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Steps 5, 6 and 8: verify the CSV bytes against the manifest digest, parse them against
 * the exact 18-column header, and project the COLLECTIONS rows for this context.
 *
 * <p>The digest is taken over the BYTES as committed, before any parsing, so a checksum
 * failure is a statement about the file rather than about this reader's idea of it. The
 * data row count must equal the manifest's, which counts DATA rows and excludes the
 * header: a loader that counts the header reads one too many and fails, which is the
 * intended direction.
 */
public final class AccountCsvReader {

    static final String CSV_FILE = "account.csv";

    /** The projection discriminator this context keeps. The MANDATES rows are a different shape. */
    static final String COLLECTIONS_SHAPE = "COLLECTIONS";

    static final List<String> HEADER = List.of("shape", "account_number", "product_code", "status",
            "account_type_code", "app_no", "acc_type", "branch_code", "balance", "max_credit_limit",
            "cancel_reason", "country_id", "edr_ind", "pre_ind", "process_status", "status_reason",
            "ucn", "client_id");

    private AccountCsvReader() {
    }

    public static List<AccountReferenceRow> read(final Path directory,
                                                 final AccountReferenceManifest manifest) {
        Path csv = directory.resolve(CSV_FILE);
        byte[] bytes = readBytes(csv);
        requireChecksum(csv, bytes, manifest.checksum());
        List<String> lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\r?\n", -1));
        return project(csv, dataLines(csv, lines, manifest), manifest);
    }

    private static byte[] readBytes(final Path csv) {
        try {
            return Files.readAllBytes(csv);
        } catch (IOException e) {
            throw new AccountArtifactException(
                    "cannot read " + csv.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    /** Step 5: both digests are printed, because "mismatch" alone sends the operator back to the file. */
    private static void requireChecksum(final Path csv, final byte[] bytes, final String expected) {
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (!actual.equalsIgnoreCase(expected)) {
            throw new AccountArtifactException("checksum.sha256 mismatch for " + csv.toAbsolutePath()
                    + ": manifest declares " + expected + " and the bytes hash to " + actual);
        }
    }

    /** Step 6: exact header, in order, and exactly the declared number of data rows. */
    private static List<String> dataLines(final Path csv, final List<String> lines,
                                          final AccountReferenceManifest manifest) {
        List<String> present = lines.stream().filter(line -> !line.isBlank()).toList();
        if (present.isEmpty()) {
            throw new AccountArtifactException(csv.toAbsolutePath() + " is empty");
        }
        List<String> header = List.of(present.getFirst().split(",", -1));
        if (!HEADER.equals(header)) {
            throw new AccountArtifactException("account.csv header mismatch in " + csv.toAbsolutePath()
                    + ": expected " + HEADER + " and found " + header);
        }
        List<String> data = present.subList(1, present.size());
        if (data.size() != manifest.rowCount()) {
            throw new AccountArtifactException("account.csv row count mismatch in "
                    + csv.toAbsolutePath() + ": manifest declares row.count=" + manifest.rowCount()
                    + " data rows and the file carries " + data.size());
        }
        return data;
    }

    /** Step 8: this context's projection only, and an EMPTY projection is a FAILURE. */
    private static List<AccountReferenceRow> project(final Path csv, final List<String> data,
                                                     final AccountReferenceManifest manifest) {
        List<AccountReferenceRow> rows = new ArrayList<>();
        for (String line : data) {
            String[] cells = line.split(",", -1);
            if (cells.length != HEADER.size()) {
                throw new AccountArtifactException("account.csv row has " + cells.length
                        + " cells where the header declares " + HEADER.size() + ": " + line);
            }
            if (COLLECTIONS_SHAPE.equals(cells[0])) {
                rows.add(AccountCsvRowMapper.map(cells));
            }
        }
        if (rows.isEmpty()) {
            throw new AccountArtifactException("account.csv in " + csv.toAbsolutePath() + " (dataset "
                    + manifest.datasetVersion() + ") carries no shape=" + COLLECTIONS_SHAPE + " rows;"
                    + " a loader that finds nothing and reports success is the defect this check exists"
                    + " to remove");
        }
        return rows;
    }

    /** Cell-to-row mapping, kept separate so the reader above stays about the FILE. */
    static final class AccountCsvRowMapper {

        private AccountCsvRowMapper() {
        }

        static AccountReferenceRow map(final String[] cells) {
            return new AccountReferenceRow(
                    required(cells, 1), required(cells, 2), required(cells, 3),
                    required(cells, 5), required(cells, 6), required(cells, 7),
                    decimal(cells, 8), decimal(cells, 9), optional(cells, 10),
                    number(cells, 11), bool(cells, 12), bool(cells, 13),
                    required(cells, 14), optional(cells, 15),
                    required(cells, 16), number(cells, 17));
        }

        /** Empty means ABSENT, and absent is not allowed in a column the table declares NOT NULL. */
        private static String required(final String[] cells, final int index) {
            String value = cells[index].strip();
            if (value.isEmpty()) {
                throw new AccountArtifactException("column '" + HEADER.get(index) + "' is empty on a "
                        + COLLECTIONS_SHAPE + " row, and dcre_col.account declares it NOT NULL");
            }
            return value;
        }

        private static String optional(final String[] cells, final int index) {
            String value = cells[index].strip();
            return value.isEmpty() ? null : value;
        }

        private static BigDecimal decimal(final String[] cells, final int index) {
            String value = cells[index].strip();
            try {
                return value.isEmpty() ? null : new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new AccountArtifactException("column '" + HEADER.get(index)
                        + "' is not a decimal, was '" + value + "'", e);
            }
        }

        private static long number(final String[] cells, final int index) {
            String value = required(cells, index);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new AccountArtifactException("column '" + HEADER.get(index)
                        + "' is not an integer, was '" + value + "'", e);
            }
        }

        private static boolean bool(final String[] cells, final int index) {
            String value = required(cells, index);
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.parseBoolean(value);
            }
            throw new AccountArtifactException("column '" + HEADER.get(index)
                    + "' must be true or false, was '" + value + "'");
        }
    }
}
