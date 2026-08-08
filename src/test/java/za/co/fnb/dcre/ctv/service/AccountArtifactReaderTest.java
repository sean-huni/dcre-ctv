package za.co.fnb.dcre.ctv.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import za.co.fnb.dcre.ctv.config.AccountReferenceProperties;
import za.co.fnb.dcre.ctv.domain.AccountArtifact;
import za.co.fnb.dcre.ctv.domain.AccountArtifactException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every failure path of the artifact read, each asserting the SPECIFIC message rather
 * than merely that an exception was thrown. A test that passes for several reasons cannot
 * detect the loss of any one of them, so each case below breaks exactly ONE thing about an
 * otherwise valid artifact, built by {@link AccountArtifactFixture} rather than pasted.
 *
 * <p>Every one of these was seen RED by structurally removing the control it guards and
 * re-running, not by reasoning that it would fail.
 */
class AccountArtifactReaderTest {

    @TempDir
    Path root;

    private AccountArtifactReader readerFor(final String version, final Duration maxAge) {
        return new AccountArtifactReader(
                new AccountReferenceProperties(root.toString(), version, maxAge));
    }

    private AccountArtifactReader reader() {
        return readerFor(AccountArtifactFixture.VERSION, null);
    }

    private static String message(final Executable call) {
        return assertThrows(AccountArtifactException.class, call::run).getMessage();
    }

    private interface Executable {
        void run();
    }

    // ---------------------------------------------------------------- 1. directory absent

    @Test
    void anAbsentArtifactDirectoryFailsAndNeverProceeds() {
        String message = message(() -> reader().read());
        assertTrue(message.contains("absent or is not a directory"),
                "an absent artifact is a FAILURE, never 'nothing to load', was: " + message);
        assertTrue(message.contains(AccountArtifactFixture.VERSION),
                "the message names the directory it looked for, was: " + message);
    }

    @Test
    void anArtifactPathThatIsAFileRatherThanADirectoryFails() throws Exception {
        Files.writeString(root.resolve(AccountArtifactFixture.VERSION), "not a directory");
        assertTrue(message(() -> reader().read()).contains("absent or is not a directory"));
    }

    // ---------------------------------------------------------------- 2. checksum

    @Test
    void aChecksumThatDoesNotMatchTheCsvBytesFailsAndPrintsBothDigests() throws Exception {
        Path directory = AccountArtifactFixture.write(
                root, AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW));
        // Break ONE thing: the bytes change, and nothing else does. Same row count, same
        // header, same versions, so only the digest can be what fails.
        Path csv = directory.resolve("account.csv");
        String tampered = Files.readString(csv).replace("75000.00", "99000.00");
        Files.writeString(csv, tampered);
        String actual = AccountArtifactFixture.sha256(tampered.getBytes(StandardCharsets.UTF_8));

        String message = message(() -> reader().read());
        assertTrue(message.contains("checksum.sha256 mismatch"), message);
        assertTrue(message.contains(actual),
                "both digests must be printed, or the operator re-reads the file the loader"
                        + " already read; was: " + message);
    }

    // ---------------------------------------------------------------- 3 and 4. versions

    @Test
    void anUnsupportedSchemaVersionFailsNamingBothNumbers() {
        AccountArtifactFixture.write(root, AccountArtifactFixture.VERSION,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW),
                Map.of("schema.version", "2"));
        String message = message(() -> reader().read());
        assertTrue(message.contains("schema.version mismatch") && message.contains("supports 1")
                        && message.contains("declares 2"),
                "both numbers, was: " + message);
    }

    @Test
    void aDatasetVersionOtherThanTheOneThisServiceExpectsFails() {
        // The directory is the one configured, and the manifest INSIDE it declares another
        // version. This is the case a "newest directory" loader could never catch.
        AccountArtifactFixture.write(root, AccountArtifactFixture.VERSION,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW),
                Map.of("dataset.version", "2026.08.10-001"));
        String message = message(() -> reader().read());
        assertTrue(message.contains("dataset.version mismatch")
                        && message.contains(AccountArtifactFixture.VERSION)
                        && message.contains("2026.08.10-001"),
                "both versions, was: " + message);
    }

    @Test
    void aMissingManifestKeyFails() {
        Path directory = AccountArtifactFixture.write(
                root, AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW));
        rewriteManifestWithout(directory, "source.id");
        assertTrue(message(() -> reader().read()).contains("missing mandatory key 'source.id'"));
    }

    // ---------------------------------------------------------------- 6. header and counts

    @Test
    void aHeaderThatIsNotTheEighteenDeclaredColumnsInOrderFails() {
        String swapped = AccountArtifactFixture.HEADER
                .replace("shape,account_number", "account_number,shape");
        AccountArtifactFixture.write(root, AccountArtifactFixture.VERSION,
                swapped + "\n" + AccountArtifactFixture.COLLECTIONS_ROW + "\n", Map.of());
        assertTrue(message(() -> reader().read()).contains("header mismatch"));
    }

    @Test
    void aDataRowCountOtherThanTheManifestsFails() {
        AccountArtifactFixture.write(root, AccountArtifactFixture.VERSION,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW),
                Map.of("row.count", "2"));
        String message = message(() -> reader().read());
        assertTrue(message.contains("row count mismatch") && message.contains("row.count=2")
                && message.contains("carries 1"), message);
    }

    // ---------------------------------------------------------------- 8. empty projection

    @Test
    void anArtifactWithNoRowsOfThisContextsShapeFails() {
        // The artifact is entirely VALID: manifest complete, versions right, checksum
        // right, header right, count right. It just holds no COLLECTIONS rows. A loader
        // that reported success here is the whole defect class this wave removes.
        AccountArtifactFixture.write(root,
                AccountArtifactFixture.csv(AccountArtifactFixture.MANDATES_ROW));
        String message = message(() -> reader().read());
        assertTrue(message.contains("no shape=COLLECTIONS rows"), message);
    }

    @Test
    void theMandatesRowsAreNotWidenedIntoThisContextsProjection() {
        // Control for the case above, and the assertion that the two shapes stay disjoint.
        AccountArtifactFixture.write(root, AccountArtifactFixture.csv(
                AccountArtifactFixture.MANDATES_ROW, AccountArtifactFixture.COLLECTIONS_ROW));
        AccountArtifact artifact = reader().read();
        assertEquals(1, artifact.rows().size(), "only the COLLECTIONS row is materialised");
        assertEquals("62114052700219584", artifact.rows().getFirst().accountNumber());
        assertEquals(2, artifact.manifest().rowCount(),
                "the manifest still describes the WHOLE artifact, both shapes");
    }

    // ---------------------------------------------------------------- 7. max-age, inert

    @Test
    void anUnsetMaxAgeProceedsAndSaysOnEveryRunThatTheCheckIsInert() {
        AccountArtifactFixture.write(root,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW));
        Logger readerLog = (Logger) LoggerFactory.getLogger(ReferenceManifestReader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        readerLog.addAppender(appender);
        try {
            // Years after publication, and it still proceeds: nothing is enforced yet.
            AccountArtifact artifact = readerFor(AccountArtifactFixture.VERSION, null)
                    .read(Instant.parse("2030-01-01T00:00:00Z"));
            assertEquals(1, artifact.rows().size(), "an inert check does not block the load");

            List<String> infos = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("freshness check INERT"))
                    .toList();
            assertEquals(1, infos.size(),
                    "silence is indistinguishable from a check that ran and passed, so the inert"
                            + " state is stated on EVERY run; INFOs seen: " + appender.list);
            assertTrue(infos.getFirst().contains("A-4"),
                    "the log names the open question that owns the number, was: " + infos.getFirst());
        } finally {
            readerLog.detachAppender(appender);
        }
    }

    @Test
    void aSetMaxAgeThatIsExceededFailsTheLoad() {
        AccountArtifactFixture.write(root,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW));
        String message = message(() -> readerFor(AccountArtifactFixture.VERSION, Duration.ofDays(7))
                .read(Instant.parse("2026-09-09T00:00:00Z")));
        assertTrue(message.contains("stale") && message.contains("2026-08-09T00:00:00Z"),
                "the failure names the publication instant and the limit, was: " + message);
    }

    @Test
    void aSetMaxAgeThatIsNotExceededProceeds() {
        // Control: proves the failure above is about the AGE, not about maxAge being set.
        AccountArtifactFixture.write(root,
                AccountArtifactFixture.csv(AccountArtifactFixture.COLLECTIONS_ROW));
        assertEquals(1, readerFor(AccountArtifactFixture.VERSION, Duration.ofDays(7))
                .read(Instant.parse("2026-08-10T00:00:00Z")).rows().size());
    }

    private static void rewriteManifestWithout(final Path directory, final String key) {
        try {
            Path manifest = directory.resolve("manifest.properties");
            String kept = Files.readAllLines(manifest).stream()
                    .filter(line -> !line.startsWith(key + "="))
                    .reduce("", (a, b) -> a + b + "\n");
            Files.writeString(manifest, kept);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
