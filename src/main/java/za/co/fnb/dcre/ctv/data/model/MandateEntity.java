package za.co.fnb.dcre.ctv.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read model over the mandate projection (MSR is the production writer, R-10). */
@Table("mandate")
public class MandateEntity {

    @Id
    private UUID id;
    private String mandateRef;
    private String contractRef;
    private String creditorAccount;
    private String status;
    private LocalDate startDate;
    private LocalDate expiryDate;
    private BigDecimal maxCollectionAmount;

    public UUID getId() { return id; }
    public String getMandateRef() { return mandateRef; }
    public String getContractRef() { return contractRef; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getStatus() { return status; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public BigDecimal getMaxCollectionAmount() { return maxCollectionAmount; }
}
