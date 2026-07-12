package za.co.fnb.dcre.ctv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

@Table("validation_log")
public class ValidationLogEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String outcome;
    private String detail;

    public static ValidationLogEntity of(UUID arrivalId, int sequence, String outcome) {
        ValidationLogEntity e = new ValidationLogEntity();
        e.arrivalId = arrivalId;
        e.sequence = sequence;
        e.outcome = outcome;
        return e;
    }

    public UUID getArrivalId() { return arrivalId; }
    public Integer getSequence() { return sequence; }
    public String getOutcome() { return outcome; }
    public String getDetail() { return detail; }
}
