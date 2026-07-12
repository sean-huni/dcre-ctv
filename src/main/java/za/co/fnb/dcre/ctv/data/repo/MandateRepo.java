package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ctv.data.model.MandateEntity;

import java.util.List;
import java.util.UUID;

public interface MandateRepo extends CrudRepository<MandateEntity, UUID> {

    /** Deterministic order = oracle insertion-order semantics (mandate_ref is serial). */
    List<MandateEntity> findAllByOrderByMandateRef();
}
