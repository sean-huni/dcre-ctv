package za.co.fnb.dcre.ctv.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import za.co.fnb.dcre.ctv.ManProjectionFixture;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Same Spring Boot test setup as CtvManifestParityTest (DC flow default):
 * one static CockroachDB container per context.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        // R-41 acceptance-mode fixture: FNBRF01 overridden to PARTIAL, every
        // other client (e.g. FNBCC01) falls to the ALL_OR_NOTHING default.
        "dcre.ctv.acceptance-mode.clients.[FNBRF01]=PARTIAL"})
public class CucumberSpringConfig {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        ManProjectionFixture.create(CRDB);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        // SCRUM-107 (review C1): declare the mandates store against THIS container.
        // The committed default is localhost:26257; inheriting it makes the suite
        // depend on whatever happens to be listening on the build machine.
        registry.add("dcre.ctv.mandates-db-url", () -> ManProjectionFixture.url(CRDB));
        registry.add("dcre.ctv.mandates-db-user", CRDB::getUsername);
        registry.add("dcre.ctv.mandates-db-password", CRDB::getPassword);
    }
}
