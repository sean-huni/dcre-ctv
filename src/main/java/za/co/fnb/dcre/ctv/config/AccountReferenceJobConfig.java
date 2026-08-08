package za.co.fnb.dcre.ctv.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.ctv.service.AccountReferenceLoadTasklet;

/**
 * CTV's SECOND job: {@code accountReferenceLoadJob}, one step, which materialises the
 * versioned account reference artifact into {@code dcre_col.account}.
 *
 * <p><b>It is not the default, and the two jobs are selected by name using Boot's own
 * mechanism.</b> {@code JobLauncherApplicationRunner} runs EVERY {@code Job} bean in the
 * context unless {@code spring.batch.job.name} names one, so the moment a second job bean
 * exists an unset property would silently start running the loader on every arrival. The
 * committed default in application.yml is therefore
 * {@code spring.batch.job.name: ${DCRE_CTV_JOB_NAME:ctvJob}}: the validation flow is
 * unchanged for every existing caller, and a loader run is
 * {@code DCRE_CTV_JOB_NAME=accountReferenceLoadJob}. Nothing bespoke was invented for the
 * selection, and {@code CtvJobSelectionTest} asserts the default resolves to the
 * validation job rather than trusting the yml to be read correctly.
 *
 * <p>No {@code OutcomeSeamListener} and no {@code HeartbeatWriter}, deliberately, unlike
 * {@code ctvJob}. Both exist for a pipeline STAGE: the seam file is the business verdict
 * AGT reads for an arrival, and the heartbeat tracks a stage's liveness. This job serves
 * no arrival and is on no diagram sheet; emitting a BUSINESS_ACCEPTED seam for it would
 * put a verdict in the exchange for an arrival that does not exist.
 *
 * <p>The transaction the step opens IS the one transaction the loader contract requires:
 * the delete, the inserts and the load record all commit together or none of them do.
 * There is deliberately no {@code CrdbRetryExceptionHandler} here. That handler exists for
 * transient 40001 aborts under partitioned write contention; this step writes once, and a
 * defective artifact is not made valid by five more attempts.
 */
@Configuration
@EnableConfigurationProperties(AccountReferenceProperties.class)
public class AccountReferenceJobConfig {

    @Bean
    public Step loadAccountReferenceStep(final JobRepository repo, final PlatformTransactionManager tx,
                                         final AccountReferenceLoadTasklet tasklet) {
        return new StepBuilder("loadAccountReferenceStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Job accountReferenceLoadJob(final JobRepository repo, final Step loadAccountReferenceStep) {
        return new JobBuilder("accountReferenceLoadJob", repo)
                .start(loadAccountReferenceStep)
                .build();
    }
}
