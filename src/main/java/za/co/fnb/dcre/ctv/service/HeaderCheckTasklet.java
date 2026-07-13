package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter for tier 1 (R-19): header + count check. The FILE_FATAL
 * exit path is the pre-M7 contract AGT's outcome seam depends on: exit status
 * string FILE_FATAL, fileFatalReason in the job execution context.
 */
@Component
public class HeaderCheckTasklet implements Tasklet {

    public static final String EXIT_FILE_FATAL = "FILE_FATAL";

    private final ValidationService service;

    public HeaderCheckTasklet(ValidationService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        var context = chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
        switch (service.checkHeader(arrivalId)) {
            case ValidationService.HeaderCheck.FileFatal fatal -> {
                context.putString("fileFatalReason", fatal.reason());
                contribution.setExitStatus(new ExitStatus(EXIT_FILE_FATAL));
            }
            case ValidationService.HeaderCheck.Ok ok -> {
                context.putLong("txCount", ok.txCount());
                context.putString("clientToken", ok.clientToken());
            }
        }
        return RepeatStatus.FINISHED;
    }
}
