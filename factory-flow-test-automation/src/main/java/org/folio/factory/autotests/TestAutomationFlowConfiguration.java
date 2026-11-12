package org.folio.factory.autotests;

import org.folio.factory.autotests.cli.ClaudeCodeCli;
import org.folio.factory.autotests.cli.CodingAgentCli;
import org.folio.factory.autotests.worker.AutomateCasesAgentWorker;
import org.folio.factory.autotests.worker.AutomationPublishAgentWorker;
import org.folio.factory.autotests.worker.AutomationSelectAgentWorker;
import org.folio.factory.autotests.worker.CypressDraftTestAutomationAgentWorker;
import org.folio.factory.autotests.worker.CypressTestAutomationGenerationAgentWorker;
import org.folio.factory.autotests.worker.CypressTestAutomationReviewAgentWorker;
import org.folio.factory.autotests.worker.CypressTestFrameworkExplorerAgentWorker;
import org.folio.factory.autotests.workspace.AutomationWorkspace;
import org.folio.factory.autotests.workspace.PreCommitChecks;
import org.folio.factory.autotests.workspace.RunEnvironments;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailProperties;
import org.folio.factory.core.service.AuditLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wiring for the automation steps of the {@code cypress-factory} flow (steps automation-select →
 * automate → gate 2). The flow itself is data; this class only assembles the worker beans.
 */
@Configuration
@EnableConfigurationProperties(TestAutomationFlowProperties.class)
public class TestAutomationFlowConfiguration {

    @Bean
    public AutomationWorkspace automationWorkspace(TestAutomationFlowProperties properties) {
        return new AutomationWorkspace(Path.of(System.getProperty("user.dir")).resolve(properties.workspaceDir()));
    }

    @Bean
    public CodingAgentCli codingAgentCli(TestAutomationFlowProperties properties) {
        return new ClaudeCodeCli(properties.cliTimeoutSeconds(), properties.cliModel(), properties.cliEffort());
    }

    @Bean
    public AutomationSelectAgentWorker automationSelectAgentWorker(
            @Qualifier("testRailConnector") TestRailConnector testRail, TestAutomationFlowProperties properties) {
        return new AutomationSelectAgentWorker(testRail, properties);
    }

    @Bean
    public CypressTestFrameworkExplorerAgentWorker cypressTestFrameworkExplorerAgentWorker(
            ChatClient.Builder chatClientBuilder, AutomationWorkspace workspace) {
        return new CypressTestFrameworkExplorerAgentWorker(chatClientBuilder.build(), workspace);
    }

    @Bean
    public CypressTestAutomationGenerationAgentWorker cypressTestAutomationGenerationAgentWorker(
            ChatClient.Builder chatClientBuilder, CodingAgentCli cli, AutomationWorkspace workspace) {
        return new CypressTestAutomationGenerationAgentWorker(chatClientBuilder.build(), cli, workspace);
    }

    @Bean
    public CypressTestAutomationReviewAgentWorker cypressTestAutomationReviewAgentWorker(
            ChatClient.Builder chatClientBuilder, CodingAgentCli cli, AutomationWorkspace workspace,
            TestAutomationFlowProperties properties) {
        return new CypressTestAutomationReviewAgentWorker(chatClientBuilder.build(), cli, workspace,
                properties.runSpecs());
    }

    @Bean
    public AutomateCasesAgentWorker automateCasesAgentWorker(CypressTestFrameworkExplorerAgentWorker explorer,
            CypressTestAutomationGenerationAgentWorker generator, CypressTestAutomationReviewAgentWorker reviewer,
            AutomationWorkspace workspace, TestAutomationFlowProperties properties) {
        RunEnvironments environments = properties.runSpecs()
                ? RunEnvironments.load(properties.runEnvironmentsFile()) : null;
        return new AutomateCasesAgentWorker(explorer, generator, reviewer, workspace,
                new PreCommitChecks.LintStaged(), environments);
    }

    @Bean
    public AutomationPublishAgentWorker automationPublishAgentWorker(@Qualifier("gitHubConnector") GitHubConnector gitHub,
            AutomationWorkspace workspace, TestAutomationFlowProperties properties,
            TestRailProperties testRail, GitHubProperties gitHubProperties, AuditLog auditLog) {
        return new AutomationPublishAgentWorker(gitHub, workspace, properties, testRail.baseUrl(),
                gitHubProperties.token(), AutomationPublishAgentWorker.gitHostFor(gitHubProperties.effectiveBaseUrl()),
                auditLog);
    }

    @Bean
    public CypressDraftTestAutomationAgentWorker CypressDraftTestAutomationAgentWorker(ChatClient.Builder chatClientBuilder,
                                                                                  TestAutomationFlowProperties properties) {
        return new CypressDraftTestAutomationAgentWorker(chatClientBuilder.build(), properties);
    }
}
