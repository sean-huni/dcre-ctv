package za.co.fnb.dcre.ctv.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** Read model over the account fixture store (AIS is the production writer, R-11). */
@Table("account")
public class AccountEntity {

    @Id
    private UUID id;
    private String accountNumber;
    private String productCode;
    private BigDecimal balance;
    private BigDecimal maxCreditLimit;
    private String processStatus;

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getProductCode() { return productCode; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getMaxCreditLimit() { return maxCreditLimit; }
    public String getProcessStatus() { return processStatus; }
}
