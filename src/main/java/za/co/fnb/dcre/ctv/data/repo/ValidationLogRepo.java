package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.ctv.data.model.ValidationLogEntity;

import java.util.UUID;

public interface ValidationLogRepo extends CrudRepository<ValidationLogEntity, UUID> {

    @Modifying
    @Query("""
            UPSERT INTO validation_log (arrival_id, sequence, outcome)
            VALUES (:#{#e.arrivalId}, :#{#e.sequence}, :#{#e.outcome})""")
    void upsert(@Param("e") ValidationLogEntity e);
}
