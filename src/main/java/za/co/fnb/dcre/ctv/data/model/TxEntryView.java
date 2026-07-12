package za.co.fnb.dcre.ctv.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** Read model over CRR's tx_entry. */
@Table("tx_entry")
public class TxEntryView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer sequence;
    private String e2e;
    private String creditorAccount;
    private String contractRef;
    private BigDecimal amount;

    public Integer getSequence() { return sequence; }
    public String getE2e() { return e2e; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getContractRef() { return contractRef; }
    public BigDecimal getAmount() { return amount; }
}
