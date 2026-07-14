package za.co.fnb.dcre.ctv.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import za.co.fnb.dcre.ctv.config.AcceptanceModeProperties.Mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-41 acceptance-mode resolution and fail-closed binding. modeFor falls to the
 * default for null/blank/unmapped tokens; an unknown configured mode fails
 * startup (no silent fallthrough to a permissive mode).
 */
class AcceptanceModePropertiesTest {

    @Test
    void modeForFallsToDefaultForNullBlankAndUnmapped() {
        AcceptanceModeProperties props = new AcceptanceModeProperties();
        props.setDefault(Mode.ALL_OR_NOTHING);
        props.getClients().put("FNBRF01", Mode.PARTIAL);

        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor(null));
        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor("   "));
        assertEquals(Mode.ALL_OR_NOTHING, props.modeFor("FNBCC01")); // unmapped -> default
        assertEquals(Mode.PARTIAL, props.modeFor("FNBRF01"));        // mapped override
        assertEquals(Mode.PARTIAL, props.modeFor(" FNBRF01 "));      // stripped
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @Test
    void startupFailsOnUnknownDefaultMode() {
        runner.withPropertyValues("dcre.ctv.acceptance-mode.default=BOGUS")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * SCRUM-42: FNBCC02 PARTIAL is a COMMITTED working default in application.yml
     * (AGT cannot pass extra env to stage pods yet, so the in-cluster partial-flow
     * check must work without env passthrough). Binds the real classpath yml.
     */
    @Test
    void committedYmlMapsFnbcc02ToPartialAndLeavesDefaultAllOrNothing() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AcceptanceModeProperties props = context.getBean(AcceptanceModeProperties.class);
                    assertEquals(Mode.PARTIAL, props.modeFor("FNBCC02"));       // committed override
                    assertEquals(Mode.ALL_OR_NOTHING, props.getDefault());      // default untouched
                    assertEquals(Mode.ALL_OR_NOTHING, props.modeFor("FNBRF01")); // unmapped -> default
                });
    }

    @Test
    void bracketedClientKeyBindsWithCasePreserved() {
        runner.withPropertyValues(
                        "dcre.ctv.acceptance-mode.default=ALL_OR_NOTHING",
                        "dcre.ctv.acceptance-mode.clients.[FNBRF01]=PARTIAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AcceptanceModeProperties props = context.getBean(AcceptanceModeProperties.class);
                    assertEquals(Mode.PARTIAL, props.modeFor("FNBRF01"));
                    assertEquals(Mode.ALL_OR_NOTHING, props.modeFor("FNBCC01"));
                });
    }

    @Configuration
    @EnableConfigurationProperties(AcceptanceModeProperties.class)
    static class EnableProps {
    }
}
