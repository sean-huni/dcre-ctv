package za.co.fnb.dcre.ctv.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the CRDB 40001 retry wiring at the REAL Batch step level, including
 * the crux: the observed production failure is at the CHUNK-COMMIT boundary
 * ("JDBC commit; ERROR: restart transaction"), so the only injected failure
 * here is thrown from PlatformTransactionManager.doCommit itself. A mechanism
 * that does not cover commit-time aborts cannot turn these tests green.
 */
class CrdbRetryExceptionHandlerTest {

    /** Fails the first {@code failures} transaction COMMITS the way JdbcTransactionManager surfaces a CRDB 40001. */
    static final class CommitFailingTxManager extends ResourcelessTransactionManager {

        private final int failures;
        private int commits;

        CommitFailingTxManager(final int failures) {
            this.failures = failures;
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            if (++commits <= failures) {
                throw new CannotAcquireLockException(
                        "JDBC commit; ERROR: restart transaction: TransactionRetryWithProtoRefreshError:"
                                + " RETRY_ASYNC_WRITE_FAILURE");
            }
            super.doCommit(status);
        }
    }

    private StepExecution execute(final int commitFailures, final Tasklet tasklet) throws Exception {
        var repo = new ResourcelessJobRepository();
        Step step = new StepBuilder("validationWorkerStep", repo)
                .tasklet(tasklet, new CommitFailingTxManager(commitFailures))
                .exceptionHandler(new CrdbRetryExceptionHandler())
                .build();
        JobInstance instance = repo.createJobInstance("retryJob", new JobParameters());
        JobExecution jobExecution = repo.createJobExecution(instance, new JobParameters(), new ExecutionContext());
        StepExecution stepExecution = repo.createStepExecution("validationWorkerStep", jobExecution);
        step.execute(stepExecution);
        return stepExecution;
    }

    @Test
    void stepCompletesWhenCommitThrowsTransientTwiceThenSucceeds() throws Exception {
        var executions = new AtomicInteger();
        Tasklet tasklet = (contribution, chunkContext) -> {
            executions.incrementAndGet();
            return RepeatStatus.FINISHED;
        };

        StepExecution stepExecution = execute(2, tasklet);

        assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus(),
                "two commit-time 40001 aborts must be retried, not fail the step");
        assertEquals(3, executions.get(), "tasklet transaction re-runs once per aborted commit");
    }

    @Test
    void stepFailsAfterAttemptBudgetOnPersistentCommitAborts() throws Exception {
        var executions = new AtomicInteger();
        Tasklet tasklet = (contribution, chunkContext) -> {
            executions.incrementAndGet();
            return RepeatStatus.FINISHED;
        };

        StepExecution stepExecution = execute(6, tasklet);

        assertEquals(BatchStatus.FAILED, stepExecution.getStatus(),
                "persistent 40001 aborts exhaust the budget and fail the step");
        assertEquals(CrdbRetryExceptionHandler.MAX_ATTEMPTS, executions.get(),
                "retry budget is bounded");
        assertTrue(stepExecution.getFailureExceptions().stream()
                        .anyMatch(CannotAcquireLockException.class::isInstance),
                "the original transient abort is the recorded failure");
    }

    @Test
    void nonTransientFailureIsNotRetried() throws Exception {
        var executions = new AtomicInteger();
        Tasklet tasklet = (contribution, chunkContext) -> {
            executions.incrementAndGet();
            throw new IllegalStateException("business bug, not contention");
        };

        StepExecution stepExecution = execute(0, tasklet);

        assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
        assertEquals(1, executions.get(), "only TransientDataAccessException is retried");
    }
}
