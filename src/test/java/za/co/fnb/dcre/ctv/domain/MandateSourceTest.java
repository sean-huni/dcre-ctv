package za.co.fnb.dcre.ctv.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCRUM-107 (review I2): {@link MandateSource#from(String)} decides whether a CTV pod
 * starts. It is reached from a {@code @Value} constructor argument, so a rejection is a
 * bean-creation failure and the pod never serves. That makes it worth testing in both
 * directions rather than reasoning about.
 *
 * <p>The negative case is the one that matters operationally: agt hands
 * {@code DCRE_CTV_MANDATE_SOURCE} to every CTV stage pod, so a stale {@code legacy}
 * value would stop the whole collections DC flow. Failing closed with a message that
 * NAMES the cause is the point; falling back to projection silently would hide a
 * misconfiguration, and falling back to legacy would query a table that no longer exists.
 */
class MandateSourceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void absentOrBlankDefaultsToTheProjection(final String value) {
        assertEquals(MandateSource.PROJECTION, MandateSource.from(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"projection", "PROJECTION", "Projection", "  projection  "})
    void theProjectionValueParsesRegardlessOfCaseOrPadding(final String value) {
        assertEquals(MandateSource.PROJECTION, MandateSource.from(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "LEGACY", "Legacy", " legacy "})
    void theRetiredLegacyValueFailsClosedAndNamesTheCause(final String value) {
        assertThatThrownBy(() -> MandateSource.from(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCRUM-107")
                .hasMessageContaining("dcre_col.mandate")
                .hasMessageContaining("projection");
    }

    @Test
    void anUnknownValueIsRejectedRatherThanSilentlyDefaulted() {
        // Fail closed on anything unrecognised: a typo must not resolve to a working
        // gate, or the property stops meaning anything.
        assertThatThrownBy(() -> MandateSource.from("prjection"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEnumHasExactlyOneValueSoNothingSelectsAStoreThatIsGone() {
        assertThat(MandateSource.values()).containsExactly(MandateSource.PROJECTION);
    }
}
