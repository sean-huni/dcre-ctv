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
 * {@code SELECT} on {@code man_ctv_view} (R-10). The URL default is a dev
 * infrastructure address, so a masking default is safe (12FactorApp clean-clone);
 * every deployed context overrides {@code dcre.ctv.mandates-db-url}. The resolved
 * URL/user is logged once at startup so operators can spot a wired-but-wrong target.
 */
@Configuration(proxyBeanMethods = false)
public class MandatesDatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MandatesDatasourceConfig.class);

    @Bean
    MandateProjectionDao mandateProjectionDao(
            @Value("${dcre.ctv.mandates-db-url:jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable}")
            final String url,
            @Value("${dcre.ctv.mandates-db-user:root}") final String user,
            @Value("${dcre.ctv.mandates-db-password:}") final String password) {
        log.info("ctv mandate projection: dcre_man read-only gate url={} user={}", url, user);
        final DataSource dcreMan = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new MandateProjectionDao(new JdbcTemplate(dcreMan));
    }
}
