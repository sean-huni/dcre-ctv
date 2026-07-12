package za.co.fnb.dcre.ctv;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * BDD suite for the default DC flow (dcre.flow-dc=true). ENDO-mode scenarios
 * (@endo) need a different Spring context and run in CucumberEndoSuiteTest.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "za.co.fnb.dcre.ctv.bdd")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @endo")
class CucumberSuiteTest {
}
