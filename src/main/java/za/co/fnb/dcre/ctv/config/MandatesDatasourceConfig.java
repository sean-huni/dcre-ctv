package za.co.fnb.dcre.ctv.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import za.co.fnb.dcre.ctv.data.repo.MandateProjectionDao;

import javax.sql.DataSource;

/**
 * M10 T15 (SCRUM-78): the @Import-able unit that gives ctv a READ-ONLY connection
 * to the MSR-owned dcre_man projection, for the projection-mode mandate gate.
 *
 * <p><b>Usage: {@code @Import} only, never component-scanned</b> (identical caveat to
 * {@code HeartbeatDatasourceConfig}). The dcre_man {@link DataSource} is built INLINE
 * (not exposed as a bean): a standalone {@code DataSource} bean would trip Boot's
 * {@code DataSourceAutoConfiguration} ({@code @ConditionalOnMissingBean(DataSource.class)})
 * and REPLACE the primary dcre_col datasource. It is a deliberately non-pooling
 * {@link SimpleDriverDataSource}: the projection is read once per job (one as-of
 * snapshot + one read per partition range), so a pool would idle unclosed for the
 * life of the context. The connection is opened lazily, so in {@code legacy} mode
 * (the default) dcre_man is never contacted - the bean exists but {@code MandateGate}
 * short-circuits to the collections store.
 *
 * <p><b>Dedicated read-only dcre_man credentials.</b> The connection uses
 * {@code dcre.ctv.mandates-db-user} / {@code dcre.ctv.mandates-db-password} (default
 * {@code root} / blank for the dev clean-clone), NOT the primary {@code
 * spring.datasource.*} creds, so the standing-cluster ctv role can be granted only
 * {@code SELECT} on {@code man_ctv_view} (R-10). The resolved URL/user is logged once
 * at startup so operators can spot a wired-but-wrong target.
 *
 * <p><b>The dev default is guarded, not dropped.</b> This javadoc used to claim "every
 * deployed context overrides {@code dcre.ctv.mandates-db-url}", and nothing did: AGT
 * shipped no {@code DCRE_CTV_MANDATES_DB_URL}, so an in-cluster CTV in projection mode
 * quietly aimed the gate at localhost and could not read dcre_man at all (found
 * 2026-07-26, the same class of bug as mrg's {@code DCRE_COL_DB_URL}). The localhost
 * default still earns its keep: it is what lets a clean clone boot with no {@code .env}
 * (12FactorApp Alignment - https://12factor.net/). So it survives for local dev and
 * becomes FATAL in a pod: if {@code KUBERNETES_SERVICE_HOST} is set (kubelet sets it in
 * every container) and the url is still the committed dev default, the context fails at
 * start naming the variable. That keeps {@link #LOCAL_DEV_URL} the single pivot, pinned
 * to the yml by a parity test, rather than removing the default and breaking the
 * clean-clone rule for every local run.
 */
@Configuration(proxyBeanMethods = false)
public class MandatesDatasourceConfig {

    /** The committed local-dev target; kept verbatim as the placeholder default so a
     *  clean clone boots with no {@code .env}. Pinned to the yml by a parity test. */
    static final String LOCAL_DEV_URL = "jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable";

    private static final Logger log = LoggerFactory.getLogger(MandatesDatasourceConfig.class);

    @Bean
    MandateProjectionDao mandateProjectionDao(
            @Value("${dcre.ctv.mandates-db-url:" + LOCAL_DEV_URL + "}")
            final String url,
            @Value("${dcre.ctv.mandates-db-user:root}") final String user,
            @Value("${dcre.ctv.mandates-db-password:}") final String password,
            @Value("${KUBERNETES_SERVICE_HOST:}") final String clusterApiHost) {
        requireWiredTargetInCluster(url, clusterApiHost);
        log.info("ctv mandate projection: dcre_man read-only gate url={} user={}", url, user);
        final DataSource dcreMan = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new MandateProjectionDao(new JdbcTemplate(dcreMan));
    }

    /** Fail fast in a pod that is still on the local-dev default: CTV would
     *  otherwise start, log the wrong target, and fail the gate at query time. */
    private static void requireWiredTargetInCluster(final String url, final String clusterApiHost) {
        if (clusterApiHost == null || clusterApiHost.isBlank() || !LOCAL_DEV_URL.equals(url)) {
            return;
        }
        throw new IllegalStateException(
                "set DCRE_CTV_MANDATES_DB_URL: running in-cluster (KUBERNETES_SERVICE_HOST=" + clusterApiHost
                        + ") on the local-dev dcre_man default " + LOCAL_DEV_URL
                        + "; the projection mandate gate reads man_ctv_view and localhost is not it");
    }
}
