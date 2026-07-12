package za.co.fnb.dcre.ctv;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import za.co.fnb.dcre.platform.batch.ExitCodeMain;

@SpringBootApplication
@org.springframework.context.annotation.Import(za.co.fnb.dcre.platform.persistence.JdbcConfig.class)
public class CtvApplication {

    public static void main(String[] args) {
        ExitCodeMain.run(CtvApplication.class, args);
    }
}
