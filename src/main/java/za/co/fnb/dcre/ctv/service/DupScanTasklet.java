package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Thin entry adapter for the R-41 set-based dup scan (sequential, pre-partition). */
@Component
public class DupScanTasklet implements Tasklet {

    private final DupScanService service;

    public DupScanTasklet(DupScanService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        service.scan(arrivalId);
        return RepeatStatus.FINISHED;
    }
}
