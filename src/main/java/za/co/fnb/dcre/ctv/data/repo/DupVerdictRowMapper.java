package za.co.fnb.dcre.ctv.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.ctv.data.model.DupVerdictRow;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Row mapper for the dup-verdict logging projection (non-entity read). */
public class DupVerdictRowMapper implements RowMapper<DupVerdictRow> {

    @Override
    public DupVerdictRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DupVerdictRow(rs.getInt("sequence"), rs.getString("e2e"), rs.getString("outcome"));
    }
}
