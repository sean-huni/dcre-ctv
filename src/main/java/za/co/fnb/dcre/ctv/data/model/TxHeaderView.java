package za.co.fnb.dcre.ctv.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Read model over CRR's tx_header (grants-based read, R-04/R-06). */
@Table("tx_header")
public class TxHeaderView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer txCount;
    private String businessDate;

    public UUID getArrivalId() { return arrivalId; }
    public Integer getTxCount() { return txCount; }
    public String getBusinessDate() { return businessDate; }
}
