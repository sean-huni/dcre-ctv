package za.co.fnb.dcre.ctv;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * BDD suite for ENDO mode (dcre.flow-dc=false, SCRUM-32 / A-20 draft): a
 * dedicated glue package carries the differently-configured Spring context,
 * mirroring how CtvEndoModeTest sits beside CtvManifestParityTest.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "za.co.fnb.dcre.ctv.bddendo")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@endo")
class CucumberEndoSuiteTest {
}
