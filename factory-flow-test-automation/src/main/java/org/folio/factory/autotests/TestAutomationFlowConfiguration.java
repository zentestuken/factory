package org.folio.factory.autotests;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.autotests.worker.*;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;


/**
 * Cypress Factory plugin wiring. The flow itself is data ({@code flows/test-automation-factory.yaml});
 * this class only assembles the worker beans the descriptor references.
 */
@Configuration
@EnableConfigurationProperties(TestAutomationFlowProperties.class)
public class TestAutomationFlowConfiguration {

    @Bean
    public TestRailGetTestAgentWorker testRailGetTestAgentWorker(@Qualifier("testRailConnector") TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                                                 TestAutomationFlowProperties properties, JsonMapper jsonMapper) {
        return new TestRailGetTestAgentWorker(testRail, frontmatterCodec, properties, jsonMapper);
    }

    @Bean
    public CypressTestFrameworkExplorerAgentWorker cypressTestFrameworkExplorerAgentWorker(ChatClient.Builder chatClientBuilder) {
        return new CypressTestFrameworkExplorerAgentWorker(chatClientBuilder.build());
    }

    @Bean
    public CypressTestAutomationGenerationAgentWorker cypressTestAutomationGenerationAgentWorker(ChatClient.Builder chatClientBuilder) {
        Path workspace = Path.of(System.getProperty("user.dir"), "cypressTafClone/cypress");
        var fileSystemTools = FileSystemTools.builder().allowedDirectory(workspace).build();
        var grepTool = GrepTool.builder().build();
        var globTool = GlobTool.builder().build();

        return new CypressTestAutomationGenerationAgentWorker(chatClientBuilder.defaultTools(fileSystemTools, grepTool, globTool).build());
    }

    @Bean
    public CypressTestAutomationReviewAgentWorker cypressTestAutomationReviewAgentWorker(ChatClient.Builder chatClientBuilder) {
        Path workspace = Path.of(System.getProperty("user.dir"), "cypressTafClone/cypress");
        var fileSystemTools = FileSystemTools.builder().allowedDirectory(workspace).build();
        var grepTool = GrepTool.builder().build();
        var globTool = GlobTool.builder().build();

        return new CypressTestAutomationReviewAgentWorker(chatClientBuilder.defaultTools(fileSystemTools, grepTool, globTool).build());
    }

    @Bean
    public CypressDraftTestAutomationAgentWorker CypressDraftTestAutomationAgentWorker(ChatClient.Builder chatClientBuilder,
                                                                                  TestAutomationFlowProperties properties) {
        return new CypressDraftTestAutomationAgentWorker(chatClientBuilder.build(), properties);
    }
}
