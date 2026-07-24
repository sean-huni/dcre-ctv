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
    private String mandateRef;
    private BigDecimal amount;

    public Integer getSequence() { return sequence; }
    public String getE2e() { return e2e; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getContractRef() { return contractRef; }
    /** M10 T15: the collection-to-mandate link (CRR tx_entry.mandate_ref); NULL for V1/V2 books. */
    public String getMandateRef() { return mandateRef; }
    public BigDecimal getAmount() { return amount; }
}
