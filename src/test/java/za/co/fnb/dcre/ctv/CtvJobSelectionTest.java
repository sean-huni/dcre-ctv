package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CTV carries TWO jobs, and which one a bare {@code java -jar} runs is a committed
 * decision rather than an accident of bean ordering.
 *
 * <p>Boot's {@code JobLauncherApplicationRunner} runs EVERY {@code Job} bean in the
 * context unless {@code spring.batch.job.name} names one. So the moment a second job bean
 * appeared, an unset property would have started running the reference loader on every
 * arrival, silently, alongside the validation flow. That is the failure this test exists to
 * make impossible to reintroduce: it asserts the committed default resolves to
 * {@code ctvJob}, using Boot's own selection mechanism and not a bespoke one.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange"})
class CtvJobSelectionTest {

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

    /** Resolved from application.yml exactly as the runner resolves it, no default here. */
    @Value("${spring.batch.job.name}")
    String selectedJobName;

    @Autowired
    Map<String, Job> jobsByBeanName;

    @Test
    void theCommittedDefaultSelectsTheValidationFlowAndNotTheLoader() {
        assertEquals("ctvJob", selectedJobName,
                "AGT passes no job name, so the committed default IS what every stage pod runs;"
                        + " a loader run is DCRE_CTV_JOB_NAME=accountReferenceLoadJob");
    }

    @Test
    void bothJobsExistAndAreDistinctlyNamed() {
        List<String> names = jobsByBeanName.values().stream().map(Job::getName).sorted().toList();
        assertEquals(List.of("accountReferenceLoadJob", "ctvJob"), names,
                "two jobs, coexisting, selected by name");
    }

    @Test
    void theSelectedNameIsOneTheContextActuallyHolds() {
        // Guards the direction the assertion above cannot see: a typo in the yml would
        // leave the property set to a job that does not exist, and Boot would then run
        // NOTHING while exiting 0.
        assertTrue(jobsByBeanName.values().stream().anyMatch(job -> job.getName().equals(selectedJobName)),
                "spring.batch.job.name=" + selectedJobName + " matches no Job bean");
    }
}
