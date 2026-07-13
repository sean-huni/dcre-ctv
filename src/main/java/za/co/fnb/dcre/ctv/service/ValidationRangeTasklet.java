package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import java.util.UUID;

/**
 * Thin entry adapter for one partition worker (R-41): validates the sequence
 * range carved out by the partitioner. Step-scoped bean, one instance per
 * partition (see CtvJobConfig).
 */
public class ValidationRangeTasklet implements Tasklet {

    private final ValidationService service;
    private final UUID arrivalId;
    private final int fromSeq;
    private final int toSeq;

    public ValidationRangeTasklet(ValidationService service, UUID arrivalId, int fromSeq, int toSeq) {
        this.service = service;
        this.arrivalId = arrivalId;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        service.validateRange(arrivalId, fromSeq, toSeq);
        return RepeatStatus.FINISHED;
    }
}
