package org.folio.factory.cypressfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.cypressfactory.worker.ContextLoaderWorker;
import org.folio.factory.cypressfactory.worker.CypressExamplesLoaderWorker;
import org.folio.factory.cypressfactory.worker.E2eSpecAgentWorker;
import org.folio.factory.cypressfactory.worker.FragmentScannerWorker;
import org.folio.factory.cypressfactory.worker.ScenarioAnalysisAgentWorker;
import org.folio.factory.cypressfactory.worker.TafSyncService;
import org.folio.factory.cypressfactory.worker.TestRailSyncAgentWorker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;


/**
 * Cypress Factory plugin wiring. The flow itself is data ({@code flows/cypress-factory.yaml});
 * this class only assembles the worker beans the descriptor references.
 */
@Configuration
@EnableConfigurationProperties(CypressFlowProperties.class)
public class CypressFlowConfiguration {

    @Bean
    public ContextLoaderWorker contextLoaderWorker(GitHubConnector gitHubConnector,
                                                   JiraConnector jiraConnector,
                                                   FrontmatterCodec frontmatterCodec,
                                                   JsonMapper jsonMapper,
                                                   CypressFlowProperties properties) {
        return new ContextLoaderWorker(gitHubConnector, jiraConnector, frontmatterCodec, jsonMapper, properties);
    }

    @Bean
    public TafSyncService tafSyncService() {
        return new TafSyncService();
    }

    @Bean
    public FragmentScannerWorker fragmentScannerWorker(TafSyncService tafSyncService,
                                                        GitHubConnector gitHubConnector,
                                                        FrontmatterCodec frontmatterCodec) {
        return new FragmentScannerWorker(tafSyncService, gitHubConnector, frontmatterCodec);
    }

    @Bean
    public CypressExamplesLoaderWorker cypressExamplesLoaderWorker(GitHubConnector gitHubConnector,
                                                                    CypressFlowProperties properties) {
        return new CypressExamplesLoaderWorker(gitHubConnector, properties);
    }

    @Bean
    public ScenarioAnalysisAgentWorker scenarioAnalysisAgentWorker(ChatClient.Builder chatClientBuilder) {
        return new ScenarioAnalysisAgentWorker(chatClientBuilder.build());
    }

    @Bean
    public E2eSpecAgentWorker e2eSpecAgentWorker(ChatClient.Builder chatClientBuilder,
                                                  FrontmatterCodec frontmatterCodec,
                                                  JsonMapper jsonMapper) {
        return new E2eSpecAgentWorker(chatClientBuilder.build(), frontmatterCodec, jsonMapper);
    }

    @Bean
    public TestRailSyncAgentWorker testRailSyncAgentWorker(TestRailConnector testRailConnector,
                                                            FrontmatterCodec frontmatterCodec,
                                                            CypressFlowProperties properties,
                                                            JsonMapper jsonMapper) {
        return new TestRailSyncAgentWorker(testRailConnector, frontmatterCodec, properties, jsonMapper);
    }
}
