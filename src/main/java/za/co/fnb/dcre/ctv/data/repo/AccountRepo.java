package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ctv.data.model.AccountEntity;

import java.util.UUID;

public interface AccountRepo extends CrudRepository<AccountEntity, UUID> {
}
