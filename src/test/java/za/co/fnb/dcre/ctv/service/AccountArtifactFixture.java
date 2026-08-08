package za.co.fnb.dcre.ctv.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds an account reference artifact on disk for the failure-path tests.
 *
 * <p>The checksum is COMPUTED from the bytes it writes rather than pasted, so a fixture is
 * valid by construction and a checksum test has to break it DELIBERATELY. A fixture
 * carrying a stale hardcoded digest would fail the checksum check for the wrong reason,
 * and every other test in the file would then be passing on an artifact that never got
 * past step 5.
 *
 * <p>Deliberately NO default for {@code reference.max-age}: the contract forbids inventing
 * one anywhere, including here.
 */
final class AccountArtifactFixture {

    static final String VERSION = "2026.08.09-001";

    static final String HEADER = String.join(",", AccountCsvReader.HEADER);

    /** One valid COLLECTIONS row, FNBRF so the product/amount CHECK is satisfied. */
    static final String COLLECTIONS_ROW = "COLLECTIONS,62114052700219584,FNBRF,AAUT,,"
            + "2590451916851902001,CACC,250205,75000.00,,,1,false,false,ACTIVE,,100000000201,2";

    /** One MANDATES row: a DIFFERENT projection, and never part of this context's load. */
    static final String MANDATES_ROW = "MANDATES,62999999999999,FNBRF,AAUT,CACC,,,,,,,1,"
            + "false,false,ACTIVE,,100000000999,2";

    private AccountArtifactFixture() {
    }

    /** Writes {@code <root>/<version>/} with a manifest consistent with the CSV given. */
    static Path write(final Path root, final String version, final String csv,
                      final Map<String, String> manifestOverrides) {
        try {
            Path directory = Files.createDirectories(root.resolve(version));
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve("account.csv"), bytes);

            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("dataset.version", version);
            keys.put("schema.version", "1");
            keys.put("source.id", "fixture:test");
            keys.put("effective.ts", "2026-08-09T00:00:00Z");
            keys.put("publication.ts", "2026-08-09T00:00:00Z");
            keys.put("row.count", String.valueOf(dataRows(csv)));
            keys.put("checksum.sha256", sha256(bytes));
            keys.putAll(manifestOverrides);

            StringBuilder manifest = new StringBuilder();
            keys.forEach((key, value) -> manifest.append(key).append('=').append(value).append('\n'));
            Files.writeString(directory.resolve("manifest.properties"), manifest.toString());
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path write(final Path root, final String csv) {
        return write(root, VERSION, csv, Map.of());
    }

    static String csv(final String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    private static long dataRows(final String csv) {
        return csv.lines().filter(line -> !line.isBlank()).count() - 1;
    }

    static String sha256(final byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
