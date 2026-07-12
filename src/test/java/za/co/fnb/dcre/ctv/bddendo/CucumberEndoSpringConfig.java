package za.co.fnb.dcre.ctv.bddendo;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Same Spring Boot test setup as CtvEndoModeTest (dcre.flow-dc=false,
 * SCRUM-32 / A-20 draft): one static CockroachDB container per context.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange", "dcre.flow-dc=false"})
public class CucumberEndoSpringConfig {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }
}
