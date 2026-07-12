package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ctv.data.model.TxEntryView;

import java.util.List;
import java.util.UUID;

public interface TxEntryViewRepo extends CrudRepository<TxEntryView, UUID> {

    List<TxEntryView> findByArrivalIdOrderBySequence(UUID arrivalId);
}
