package org.folio.factory.autotests.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.autotests.TestAutomationFlowProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

public class TestRailGetTestAgentWorker implements AgentWorker {

    public static final String ID = "cypress-testrail-get-test";

    private final TestRailConnector testRailConnector;

    public TestRailGetTestAgentWorker(TestRailConnector testRailConnector, FrontmatterCodec frontmatterCodec,
                                   TestAutomationFlowProperties properties, JsonMapper jsonMapper) {
        this.testRailConnector = testRailConnector;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode payload = context.triggerPayload();
        String issueKey = payload.path("testCaseId").asString("");
        JsonNode testCaseDetails = testRailConnector.getTestCaseDetails(issueKey);

        return AgentResult.of("testCaseDetails", testCaseDetails.toString());
    }
}
