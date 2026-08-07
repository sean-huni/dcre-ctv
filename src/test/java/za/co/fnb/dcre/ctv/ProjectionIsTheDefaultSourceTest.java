package za.co.fnb.dcre.ctv;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107: the mandate gate had two homes, dcre_col.mandate (legacy) and the published
 * dcre_man.man_ctv_view (projection), and the LEGACY one was the default. Verified on the
 * cluster before this change: AGT_CTV_MANDATE_SOURCE and DCRE_CTV_MANDATE_SOURCE both
 * defaulted to "legacy", so collections carried a mandates table that mandates did not own.
 *
 * The projection is the authority: the mandates family owns the concept and maintains the
 * view. Asserting on the committed DEFAULT rather than on a test property, because the
 * default is what a clean clone and the cluster actually run.
 */
class ProjectionIsTheDefaultSourceTest {

    @Test
    void theCommittedDefaultMandateSourceIsTheProjection() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml)
                .as("a clean clone and the cluster both take this default; leaving it"
                        + " legacy means the local dcre_col.mandate copy stays live")
                .contains("${DCRE_CTV_MANDATE_SOURCE:projection}")
                .doesNotContain("${DCRE_CTV_MANDATE_SOURCE:legacy}");
    }
}
