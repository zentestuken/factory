package org.folio.factory.cypressfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.cypressfactory.plan.DevTeamCatalog;
import org.folio.factory.cypressfactory.plan.TestPlanParser;
import org.folio.factory.cypressfactory.prefetch.AreaDetector;
import org.folio.factory.cypressfactory.prefetch.ContextPrefetch;
import org.folio.factory.cypressfactory.prefetch.CoveragePrefetch;
import org.folio.factory.cypressfactory.prefetch.ExamplesPrefetch;
import org.folio.factory.cypressfactory.prefetch.FragmentPrefetch;
import org.folio.factory.cypressfactory.worker.PromptDump;
import org.folio.factory.cypressfactory.worker.SkillWorkspaceService;
import org.folio.factory.cypressfactory.worker.TafSyncService;
import org.folio.factory.cypressfactory.worker.TestCaseGenAgentWorker;
import org.folio.factory.cypressfactory.worker.TestRailSyncAgentWorker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

/**
 * Cypress Factory plugin wiring. The flow itself is data ({@code flows/cypress-factory.yaml});
 * this class assembles the two worker beans the descriptor references plus the deterministic
 * prefetch services the generator uses internally.
 */
@Configuration
@EnableConfigurationProperties(CypressFlowProperties.class)
public class CypressFlowConfiguration {

    // ── Workspaces (background clones; not flow steps) ────────────────────────

    @Bean
    public TafSyncService tafSyncService() {
        return new TafSyncService();
    }

    @Bean
    public SkillWorkspaceService skillWorkspaceService(GitHubConnector gitHubConnector,
                                                       CypressFlowProperties properties) {
        return new SkillWorkspaceService(gitHubConnector, properties);
    }

    // ── Prefetch services (deterministic; run before the generation call) ─────

    @Bean
    public DevTeamCatalog devTeamCatalog() {
        return new DevTeamCatalog();
    }

    @Bean
    public AreaDetector areaDetector() {
        return new AreaDetector();
    }

    @Bean
    public ContextPrefetch contextPrefetch(SkillWorkspaceService skillWorkspaceService,
                                            GitHubConnector gitHubConnector,
                                            JiraConnector jiraConnector,
                                            JsonMapper jsonMapper,
                                            CypressFlowProperties properties) {
        return new ContextPrefetch(skillWorkspaceService, gitHubConnector, jiraConnector,
                jsonMapper, properties);
    }

    @Bean
    public FragmentPrefetch fragmentPrefetch(TafSyncService tafSyncService,
                                              GitHubConnector gitHubConnector) {
        return new FragmentPrefetch(tafSyncService, gitHubConnector);
    }

    @Bean
    public CoveragePrefetch coveragePrefetch(TestRailConnector testRailConnector,
                                              CypressFlowProperties properties) {
        return new CoveragePrefetch(testRailConnector, properties);
    }

    @Bean
    public ExamplesPrefetch examplesPrefetch(SkillWorkspaceService skillWorkspaceService) {
        return new ExamplesPrefetch(skillWorkspaceService);
    }

    @Bean
    public TestPlanParser testPlanParser() {
        return new TestPlanParser();
    }

    /**
     * Reuses the existing {@code FACTORY_DEBUG_ENABLED} switch rather than introducing a
     * cypress-specific one. Off means nothing is rendered and nothing is written.
     */
    @Bean
    public PromptDump promptDump(@Value("${factory.debug.enabled:false}") boolean debugEnabled) {
        return new PromptDump(debugEnabled, Path.of(System.getProperty("user.dir"), "debugPrompts"));
    }

    // ── Workers ──────────────────────────────────────────────────────────────

    @Bean
    public TestCaseGenAgentWorker testCaseGenAgentWorker(ChatClient.Builder chatClientBuilder,
                                                          JiraConnector jiraConnector,
                                                          TestRailConnector testRailConnector,
                                                          SkillWorkspaceService skillWorkspaceService,
                                                          AreaDetector areaDetector,
                                                          ContextPrefetch contextPrefetch,
                                                          FragmentPrefetch fragmentPrefetch,
                                                          CoveragePrefetch coveragePrefetch,
                                                          ExamplesPrefetch examplesPrefetch,
                                                          FrontmatterCodec frontmatterCodec,
                                                          TestPlanParser testPlanParser,
                                                          DevTeamCatalog devTeamCatalog,
                                                          PromptDump promptDump,
                                                          CypressFlowProperties properties) {
        return new TestCaseGenAgentWorker(chatClientBuilder.build(), jiraConnector, testRailConnector,
                skillWorkspaceService, areaDetector, contextPrefetch, fragmentPrefetch,
                coveragePrefetch, examplesPrefetch, frontmatterCodec, testPlanParser, devTeamCatalog,
                promptDump, properties);
    }

    @Bean
    public TestRailSyncAgentWorker testRailSyncAgentWorker(TestRailConnector testRailConnector,
                                                            FrontmatterCodec frontmatterCodec,
                                                            CypressFlowProperties properties,
                                                            TestPlanParser testPlanParser,
                                                            DevTeamCatalog devTeamCatalog) {
        return new TestRailSyncAgentWorker(testRailConnector, frontmatterCodec, properties,
                testPlanParser, devTeamCatalog);
    }
}
