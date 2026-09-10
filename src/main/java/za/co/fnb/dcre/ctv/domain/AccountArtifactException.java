package za.co.fnb.dcre.ctv.domain;

import java.io.Serial;

/**
 * The account reference artifact is unusable, for any reason: the directory is absent or
 * unreadable, a manifest key is missing or unparseable, a declared version does not match
 * the one this service expects, the checksum does not match the CSV bytes, the header or
 * the row count disagrees with the manifest, or this context's projection is empty.
 *
 * <p>Every one of those is a FAIL. There is deliberately no "continue with what is already
 * in the table" path and no per-row skip: a loader that finds no rows and reports success
 * is the exact defect class this whole wave removes, and a loader that half-applies leaves
 * a database nobody can describe. The message always names both sides of the comparison
 * (expected and found), because a failure that says only "mismatch" sends the operator to
 * read the file the loader had already read.
 */
public class AccountArtifactException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccountArtifactException(final String message) {
        super(message);
    }

    public AccountArtifactException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
