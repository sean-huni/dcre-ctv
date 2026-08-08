package za.co.fnb.dcre.ctv.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Properties;

/**
 * The seven mandatory keys of {@code manifest.properties}, parsed. All seven are
 * required: a manifest missing any one of them is not a partially-usable manifest, it is
 * an artifact whose provenance cannot be stated, so the load fails rather than defaulting.
 *
 * <p>{@code manifest.properties} is a DATA file read with {@link Properties}, not Spring
 * configuration. The yml-only rule is about the latter and is not bent here.
 */
public record AccountReferenceManifest(String datasetVersion, int schemaVersion, String sourceId,
                                       Instant effectiveTs, Instant publicationTs, int rowCount,
                                       String checksum) {

    /** The only artifact schema this loader understands. A different one FAILS, naming both. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final List<String> KEYS = List.of("dataset.version", "schema.version",
            "source.id", "effective.ts", "publication.ts", "row.count", "checksum.sha256");

    public static AccountReferenceManifest from(final Properties properties) {
        for (String key : KEYS) {
            if (properties.getProperty(key) == null || properties.getProperty(key).isBlank()) {
                throw new AccountArtifactException(
                        "manifest.properties is missing mandatory key '" + key + "'; all of "
                                + KEYS + " are required");
            }
        }
        return new AccountReferenceManifest(
                properties.getProperty("dataset.version").strip(),
                integer(properties, "schema.version"),
                properties.getProperty("source.id").strip(),
                instant(properties, "effective.ts"),
                instant(properties, "publication.ts"),
                integer(properties, "row.count"),
                properties.getProperty("checksum.sha256").strip());
    }

    private static int integer(final Properties properties, final String key) {
        String raw = properties.getProperty(key).strip();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AccountArtifactException(
                    "manifest key '" + key + "' is not an integer, was '" + raw + "'", e);
        }
    }

    private static Instant instant(final Properties properties, final String key) {
        String raw = properties.getProperty(key).strip();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new AccountArtifactException(
                    "manifest key '" + key + "' is not an ISO-8601 instant, was '" + raw + "'", e);
        }
    }
}
