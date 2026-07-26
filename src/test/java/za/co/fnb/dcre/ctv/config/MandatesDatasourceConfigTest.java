package za.co.fnb.dcre.ctv.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import za.co.fnb.dcre.ctv.data.repo.MandateProjectionDao;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection gate's SECOND, read-only dcre_man datasource keeps a committed
 * localhost default so a clean clone boots with no {@code .env} (12FactorApp Alignment
 * - https://12factor.net/), but that default MASKED a real misconfiguration: AGT never
 * injected {@code DCRE_CTV_MANDATES_DB_URL}, so an in-cluster CTV in projection mode
 * silently aimed its mandate gate at localhost and could not read man_ctv_view at all
 * (found 2026-07-26, the same class of bug as mrg's DCRE_COL_DB_URL).
 *
 * <p>The guard is the narrowest thing that closes it: the dev default is fatal ONLY when
 * the process runs inside a pod ({@code KUBERNETES_SERVICE_HOST}, set by kubelet in every
 * container). Local dev is untouched; a wired cluster is untouched; an unwired cluster
 * fails at context start naming the variable, instead of failing the gate at query time.</p>
 */
class MandatesDatasourceConfigTest {

    /** Any non-blank value; kubelet sets the API server's ClusterIP here. */
    private static final String IN_CLUSTER = "KUBERNETES_SERVICE_HOST=10.96.0.1";

    private static final String WIRED_URL =
            "dcre.ctv.mandates-db-url=jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_man?sslmode=disable";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MandatesDatasourceConfig.class);

    @Test
    void inClusterOnTheDevDefaultFailsAtStartupNamingTheVariable() {
        runner.withPropertyValues(IN_CLUSTER).run(context -> assertThat(context)
                .getFailure()
                .hasMessageContaining("DCRE_CTV_MANDATES_DB_URL"));
    }

    @Test
    void inClusterWithTheUrlWiredStartsNormally() {
        runner.withPropertyValues(IN_CLUSTER, WIRED_URL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MandateProjectionDao.class);
        });
    }

    @Test
    void localDevKeepsTheCommittedDefaultAndBoots() {
        // Clean-clone rule: no .env, no cluster, still boots. The guard must not
        // turn the local inner loop into a mandatory-env chore.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MandateProjectionDao.class);
        });
    }

    @Test
    void theGuardedConstantIsTheCommittedYmlDefault() {
        // Parity: the guard fires on equality with the dev default, so a yml edit
        // that changed the default without changing the constant would silently
        // disarm it (dev-credential-banner redaction-guard pattern).
        assertThat(applicationYml())
                .contains("mandates-db-url: ${DCRE_CTV_MANDATES_DB_URL:"
                        + MandatesDatasourceConfig.LOCAL_DEV_URL + "}");
    }

    private static String applicationYml() {
        try (InputStream in = MandatesDatasourceConfig.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("ctv application.yml on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read application.yml", e);
        }
    }
}
