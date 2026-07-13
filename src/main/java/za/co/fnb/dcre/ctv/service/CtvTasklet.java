package za.co.fnb.dcre.ctv.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.config.AcceptanceModeProperties;
import za.co.fnb.dcre.ctv.config.AcceptanceModeProperties.Mode;
import za.co.fnb.dcre.ctv.data.repo.ValidationLogRepo;

import java.util.UUID;

/**
 * Verdict rollup (R-41): aggregates the durable validation_log into the job's
 * business exit status by the client's acceptance mode. ctvVerdict in the
 * execution context keeps the pre-M7 content-verdict semantics
 * (BUSINESS_PARTIAL / BUSINESS_ACCEPTED); the acceptance decision rides the
 * exit status, which the seam file carries to AGT.
 */
@Component
public class CtvTasklet implements Tasklet {

    public static final String EXIT_BUSINESS_FILE_REJECTED = "BUSINESS_FILE_REJECTED";
    public static final String EXIT_BUSINESS_PARTIAL = "BUSINESS_PARTIAL";
    public static final String EXIT_BUSINESS_ACCEPTED = "BUSINESS_ACCEPTED";

    private final ValidationLogRepo verdicts;
    private final AcceptanceModeProperties acceptanceMode;

    public CtvTasklet(ValidationLogRepo verdicts, AcceptanceModeProperties acceptanceMode) {
        this.verdicts = verdicts;
        this.acceptanceMode = acceptanceMode;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        var context = chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();

        int fails = verdicts.countFailsForArrival(arrivalId);
        String contentVerdict = fails > 0 ? EXIT_BUSINESS_PARTIAL : EXIT_BUSINESS_ACCEPTED;
        context.putString("ctvVerdict", contentVerdict);

        Mode mode = acceptanceMode.modeFor(context.getString("clientToken", ""));
        String exit = fails > 0 && mode == Mode.ALL_OR_NOTHING ? EXIT_BUSINESS_FILE_REJECTED : contentVerdict;
        contribution.setExitStatus(new ExitStatus(exit));
        return RepeatStatus.FINISHED;
    }
}
