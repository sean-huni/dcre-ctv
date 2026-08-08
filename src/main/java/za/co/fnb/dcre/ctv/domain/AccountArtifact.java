package za.co.fnb.dcre.ctv.domain;

import java.util.List;

/**
 * A verified artifact: the manifest whose seven keys parsed, whose versions matched the
 * ones this service expects and whose checksum matched the CSV bytes, together with THIS
 * context's projection of those bytes.
 *
 * <p>Nothing constructs one of these without having passed every check, so a caller
 * holding an {@code AccountArtifact} is holding a fact rather than an intention. The rows
 * are the COLLECTIONS projection only, and are never empty: an empty projection fails the
 * read.
 */
public record AccountArtifact(AccountReferenceManifest manifest, List<AccountReferenceRow> rows) {
}
