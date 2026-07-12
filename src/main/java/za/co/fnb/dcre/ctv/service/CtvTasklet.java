package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class CtvTasklet implements Tasklet {

    public static final String EXIT_FILE_FATAL = "FILE_FATAL";

    private final ValidationService service;

    public CtvTasklet(ValidationService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        var context = chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
        switch (service.validate(arrivalId)) {
            case ValidationService.Result.FileFatal fatal -> {
                context.putString("fileFatalReason", fatal.reason());
                contribution.setExitStatus(new ExitStatus(EXIT_FILE_FATAL));
            }
            case ValidationService.Result.Verdicts verdicts -> context.putString("ctvVerdict",
                    verdicts.anyFail() ? "BUSINESS_PARTIAL" : "BUSINESS_ACCEPTED");
        }
        return RepeatStatus.FINISHED;
    }
}
