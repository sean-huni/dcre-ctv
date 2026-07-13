package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;

import java.util.List;

/**
 * Set-oriented verdict writes (R-41): one JDBC batch per partition chunk
 * instead of a round trip per row. Same upsert SQL text as
 * {@link ValidationLogRepo#upsert}, keyed (arrival_id, sequence) per R-05.
 */
@Component
public class ValidationLogBatchDao {

    private static final int BATCH_SIZE = 500;

    private static final String UPSERT = """
            INSERT INTO validation_log (arrival_id, sequence, outcome)
            VALUES (?,?,?)
            ON CONFLICT (arrival_id, sequence) DO UPDATE SET outcome = EXCLUDED.outcome""";

    private final JdbcTemplate jdbc;

    public ValidationLogBatchDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<ValidationLogEntity> verdicts) {
        jdbc.batchUpdate(UPSERT, verdicts, BATCH_SIZE, (ps, e) -> {
            ps.setObject(1, e.getArrivalId());
            ps.setInt(2, e.getSequence());
            ps.setString(3, e.getOutcome());
        });
    }
}
