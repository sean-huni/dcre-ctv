package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Thin tasklet (R-04 layering: tasklets carry no business logic) for
 * {@code loadAccountReferenceStep}. It resolves the Batch execution id, delegates, and
 * publishes the applied count into the job execution context so a caller can read what a
 * run materialised without querying the table.
 *
 * <p>Nothing is caught. An {@code AccountArtifactException} propagates, the step
 * transaction rolls back, the step and the job FAIL, and AGT sees a failed job. That is
 * the intended shape: the alternative, a load that swallows a defect and reports success,
 * is the defect class this loader exists to remove.
 */
@Component
public class AccountReferenceLoadTasklet implements Tasklet {

    /** Job-execution-context key carrying the rows this run materialised. */
    public static final String APPLIED_ROW_COUNT = "accountReferenceAppliedRowCount";

    private final AccountReferenceLoadService loadService;

    public AccountReferenceLoadTasklet(final AccountReferenceLoadService loadService) {
        this.loadService = loadService;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getId();
        int applied = loadService.load(jobExecutionId);
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt(APPLIED_ROW_COUNT, applied);
        return RepeatStatus.FINISHED;
    }
}
