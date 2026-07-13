package za.co.fnb.dcre.ctv.config;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.ctv.batch.SequenceRangePartitioner;
import za.co.fnb.dcre.ctv.service.CtvTasklet;
import za.co.fnb.dcre.ctv.service.DupScanTasklet;
import za.co.fnb.dcre.ctv.service.HeaderCheckTasklet;
import za.co.fnb.dcre.ctv.service.ValidationRangeTasklet;
import za.co.fnb.dcre.ctv.service.ValidationService;
import za.co.fnb.dcre.platform.batch.OutcomeFileWriter;
import za.co.fnb.dcre.platform.batch.PartitionSizer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * R-41 job shape: headerCheck (tier 1, FILE_FATAL seam unchanged) -> dupScan
 * (sequential set-based) -> partitioned per-range validation -> rollup
 * (acceptance-mode exit status).
 */
@Configuration
@EnableConfigurationProperties(AcceptanceModeProperties.class)
public class CtvJobConfig {

    @Bean
    public Step headerCheckStep(JobRepository repo, PlatformTransactionManager tx, HeaderCheckTasklet tasklet) {
        return new StepBuilder("headerCheckStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Step dupScanStep(JobRepository repo, PlatformTransactionManager tx, DupScanTasklet tasklet) {
        return new StepBuilder("dupScanStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    @JobScope
    public SequenceRangePartitioner sequenceRangePartitioner(
            @Value("#{jobExecutionContext['txCount']}") Long txCount) {
        return new SequenceRangePartitioner(txCount == null ? 0 : txCount);
    }

    @Bean
    @StepScope
    public ValidationRangeTasklet validationRangeTasklet(
            ValidationService service,
            @Value("#{jobParameters['arrival.id']}") String arrivalId,
            @Value("#{stepExecutionContext['fromSeq']}") Long fromSeq,
            @Value("#{stepExecutionContext['toSeq']}") Long toSeq,
            @Value("#{jobExecutionContext['asOfTimestamp']}") String asOfTimestamp) {
        return new ValidationRangeTasklet(service, UUID.fromString(arrivalId),
                fromSeq.intValue(), toSeq.intValue(), asOfTimestamp);
    }

    @Bean
    public Step validationWorkerStep(JobRepository repo, PlatformTransactionManager tx,
                                     ValidationRangeTasklet validationRangeTasklet) {
        return new StepBuilder("validationWorkerStep", repo).tasklet(validationRangeTasklet, tx).build();
    }

    @Bean
    public Step validationStep(JobRepository repo, Step validationWorkerStep,
                               SequenceRangePartitioner sequenceRangePartitioner,
                               @Value("${dcre.ctv.max-partitions:5}") int maxPartitions) {
        return new StepBuilder("validationStep", repo)
                .partitioner("validationWorkerStep", sequenceRangePartitioner)
                .step(validationWorkerStep)
                .gridSize(PartitionSizer.partitions(maxPartitions))
                .taskExecutor(new VirtualThreadTaskExecutor("ctv-part-"))
                .build();
    }

    @Bean
    public Step rollupStep(JobRepository repo, PlatformTransactionManager tx, CtvTasklet tasklet) {
        return new StepBuilder("rollupStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Job ctvJob(JobRepository repo, Step headerCheckStep, Step dupScanStep, Step validationStep,
                      Step rollupStep, @Value("${dcre.exchange-root}") String exchangeRoot) {
        return new JobBuilder("ctvJob", repo)
                .listener(new SeamListener(exchangeRoot))
                .start(headerCheckStep)
                    .on(HeaderCheckTasklet.EXIT_FILE_FATAL).end(HeaderCheckTasklet.EXIT_FILE_FATAL)
                .from(headerCheckStep).on("FAILED").fail()
                .from(headerCheckStep).on("*").to(dupScanStep)
                .from(dupScanStep).on("FAILED").fail()
                .from(dupScanStep).on("*").to(validationStep)
                .from(validationStep).on("FAILED").fail()
                .from(validationStep).on("*").to(rollupStep)
                .from(rollupStep).on(CtvTasklet.EXIT_BUSINESS_FILE_REJECTED)
                    .end(CtvTasklet.EXIT_BUSINESS_FILE_REJECTED)
                .from(rollupStep).on(CtvTasklet.EXIT_BUSINESS_PARTIAL).end(CtvTasklet.EXIT_BUSINESS_PARTIAL)
                .from(rollupStep).on(CtvTasklet.EXIT_BUSINESS_ACCEPTED).end(CtvTasklet.EXIT_BUSINESS_ACCEPTED)
                .from(rollupStep).on("*").fail()
                .end()
                .build();
    }

    /**
     * Seam per R-33/R-35: FILE_FATAL from tier 1 stays byte-identical
     * (fileFatalReason in the execution context); otherwise the rollup's
     * acceptance-mode exit status (BUSINESS_FILE_REJECTED / BUSINESS_PARTIAL /
     * BUSINESS_ACCEPTED) is what AGT reads.
     */
    record SeamListener(String exchangeRoot) implements JobExecutionListener {

        @Override
        public void afterJob(JobExecution execution) {
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                return; // technical death: exit code + K8s condition are the witnesses
            }
            String jobName = System.getenv().getOrDefault("JOB_NAME", "local-" + execution.getId());
            String verdict = execution.getExecutionContext().containsKey("fileFatalReason")
                    ? "BUSINESS_FILE_FATAL"
                    : execution.getExecutionContext().getString("seamVerdict", "BUSINESS_ACCEPTED");
            OutcomeFileWriter.write(Path.of(exchangeRoot), jobName, verdict);
        }
    }
}
