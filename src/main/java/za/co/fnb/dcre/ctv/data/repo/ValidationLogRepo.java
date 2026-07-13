package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.ctv.data.model.DupVerdictRow;
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;

import java.util.List;
import java.util.UUID;

public interface ValidationLogRepo extends CrudRepository<ValidationLogEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO validation_log (arrival_id, sequence, outcome)
            SELECT arrival_id, sequence, 'FAIL_DUPLICATE_E2E'
            FROM (SELECT arrival_id, sequence,
                         ROW_NUMBER() OVER (PARTITION BY e2e ORDER BY sequence) rn
                  FROM tx_entry WHERE arrival_id = :arrivalId) d
            WHERE d.rn > 1
            ON CONFLICT (arrival_id, sequence) DO NOTHING""")
    int insertE2eDupVerdicts(@Param("arrivalId") UUID arrivalId);

    @Modifying
    @Query("""
            INSERT INTO validation_log (arrival_id, sequence, outcome)
            SELECT arrival_id, sequence, 'FAIL_DUPLICATE_TX'
            FROM (SELECT arrival_id, sequence,
                         ROW_NUMBER() OVER (PARTITION BY content_hash ORDER BY sequence) rn
                  FROM tx_entry WHERE arrival_id = :arrivalId AND content_hash IS NOT NULL) d
            WHERE d.rn > 1
            ON CONFLICT (arrival_id, sequence) DO NOTHING""")
    int insertContentDupVerdicts(@Param("arrivalId") UUID arrivalId);

    /**
     * Range-scoped so partition workers never read each other's rows: the only
     * pre-existing verdicts inside a range come from the dup scan or a replay.
     */
    @Query("""
            SELECT sequence FROM validation_log
            WHERE arrival_id = :arrivalId AND sequence BETWEEN :fromSeq AND :toSeq""")
    List<Integer> sequencesForArrivalInRange(@Param("arrivalId") UUID arrivalId,
                                             @Param("fromSeq") int fromSeq,
                                             @Param("toSeq") int toSeq);

    @Query("SELECT count(*) FROM validation_log WHERE arrival_id = :arrivalId AND outcome <> 'PASS'")
    int countFailsForArrival(@Param("arrivalId") UUID arrivalId);

    @Query(value = """
            SELECT vl.sequence AS sequence, te.e2e AS e2e, vl.outcome AS outcome
            FROM validation_log vl JOIN tx_entry te USING (arrival_id, sequence)
            WHERE vl.arrival_id = :arrivalId AND vl.outcome LIKE 'FAIL_DUPLICATE%'
            ORDER BY vl.sequence""", rowMapperClass = DupVerdictRowMapper.class)
    List<DupVerdictRow> findDupVerdictRows(@Param("arrivalId") UUID arrivalId);
}
