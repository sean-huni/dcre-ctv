package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.ctv.data.model.AccountEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AccountRepo extends CrudRepository<AccountEntity, UUID> {

    /** Range-scoped snapshot load (R-41): only the accounts a partition touches. */
    List<AccountEntity> findByAccountNumberIn(Collection<String> accountNumbers);
}
