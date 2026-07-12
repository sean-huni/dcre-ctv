package za.co.fnb.dcre.ctv;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import za.co.fnb.dcre.platform.batch.ExitCodeMain;

@SpringBootApplication
public class CtvApplication {

    public static void main(String[] args) {
        ExitCodeMain.run(CtvApplication.class, args);
    }
}
