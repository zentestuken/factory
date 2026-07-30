package org.folio.factory.cypressfactory.worker;

import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CypressExamplesLoaderWorkerTest {

    // folioAiRepo blank → GitHub path skipped → classpath fallback exercised; connector is never called
    private final CypressExamplesLoaderWorker worker = new CypressExamplesLoaderWorker(
            null, new CypressFlowProperties("", "main", "", null, null, null, null));

    private AgentContext emptyContext() {
        return new AgentContext(UUID.randomUUID(), "examples-load", Map.of(),
                null, Map.of(), List.of("examples.md"));
    }

    @Test
    void loadsGoldenExamplesFromClasspath() {
        AgentResult result = worker.execute(emptyContext());

        String examples = result.outputs().get("examples.md");
        assertThat(examples).isNotBlank();
        assertThat(examples).contains("Golden Examples");
        assertThat(examples).contains("Why this is the bar");
        assertThat(examples).contains("Anti-pattern");
    }

    @Test
    void outputContainsAllThreeExamples() {
        AgentResult result = worker.execute(emptyContext());

        String examples = result.outputs().get("examples.md");
        assertThat(examples).contains("Example 1");
        assertThat(examples).contains("Example 2");
        assertThat(examples).contains("Example 3");
    }
}
