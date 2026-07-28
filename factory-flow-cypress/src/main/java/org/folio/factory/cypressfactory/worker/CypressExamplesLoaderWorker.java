package org.folio.factory.cypressfactory.worker;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads golden-example test cases from folio-eureka-ai-dev at runtime so updates
 * to the source file propagate automatically without a factory redeploy.
 *
 * Primary: GET skills/write-testrail-cases/references/examples.md from FACTORY_CYPRESS_FOLIO_AI_REPO.
 * Fallback: classpath:references/examples.md (when repo not configured or fetch fails).
 */
public class CypressExamplesLoaderWorker implements AgentWorker {

    public static final String ID = "cypress-examples-loader";

    private static final Logger log = LoggerFactory.getLogger(CypressExamplesLoaderWorker.class);

    private static final String REMOTE_PATH = "skills/write-testrail-cases/references/examples.md";
    private static final String BRANCH = "master";
    private static final String CLASSPATH_PATH = "references/examples.md";

    private final GitHubConnector gitHub;
    private final CypressFlowProperties properties;

    public CypressExamplesLoaderWorker(GitHubConnector gitHub, CypressFlowProperties properties) {
        this.gitHub = gitHub;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        if (!properties.folioAiRepo().isBlank()) {
            String content = gitHub.getFileContent(properties.folioAiRepo(), BRANCH, REMOTE_PATH);
            if (content != null && !content.isBlank()) {
                return AgentResult.of("examples.md", content);
            }
            log.warn("Could not fetch examples.md from {}/{}/{} — falling back to classpath",
                    properties.folioAiRepo(), BRANCH, REMOTE_PATH);
        }
        return loadFromClasspath();
    }

    private AgentResult loadFromClasspath() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_PATH)) {
            if (in == null) {
                log.warn("Golden examples not found at classpath:{} — examples.md will be empty", CLASSPATH_PATH);
                return AgentResult.of("examples.md", "");
            }
            return AgentResult.of("examples.md", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read golden examples from classpath:{}", CLASSPATH_PATH, e);
            return AgentResult.of("examples.md", "");
        }
    }
}
