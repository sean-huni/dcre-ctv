package za.co.fnb.dcre.ctv.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.ctv.service.CtvTasklet;
import za.co.fnb.dcre.platform.batch.OutcomeFileWriter;

import java.nio.file.Path;

@Configuration
public class CtvJobConfig {

    @Bean
    public Job ctvJob(JobRepository repo, PlatformTransactionManager tx, CtvTasklet tasklet,
                      @Value("${dcre.exchange-root}") String exchangeRoot) {
        Step verdictStep = new StepBuilder("verdictStep", repo).tasklet(tasklet, tx).build();
        return new JobBuilder("ctvJob", repo)
                .listener(new SeamListener(exchangeRoot))
                .start(verdictStep)
                .build();
    }

    /** Seam per R-33/R-35: PARTIAL/ACCEPTED from the verdict pass, FILE_FATAL from tier 1. */
    record SeamListener(String exchangeRoot) implements JobExecutionListener {

        @Override
        public void afterJob(JobExecution execution) {
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                return; // technical death: exit code + K8s condition are the witnesses
            }
            String jobName = System.getenv().getOrDefault("JOB_NAME", "local-" + execution.getId());
            String verdict = execution.getExecutionContext().containsKey("fileFatalReason")
                    ? "BUSINESS_FILE_FATAL"
                    : execution.getExecutionContext().getString("ctvVerdict", "BUSINESS_ACCEPTED");
            OutcomeFileWriter.write(Path.of(exchangeRoot), jobName, verdict);
        }
    }
}
