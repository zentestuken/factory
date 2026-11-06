package org.folio.factory.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);

    @Test
    void recordsPromptAndCompletionTokensTaggedByWorker() {
        metrics.recordLlmTokens("triage-agent", Map.of("promptTokens", 120L, "completionTokens", 45L));
        metrics.recordLlmTokens("triage-agent", Map.of("promptTokens", 30L, "completionTokens", 5L));

        assertThat(registry.counter("factory.llm.tokens", "worker", "triage-agent", "type", "prompt").count())
                .isEqualTo(150.0);
        assertThat(registry.counter("factory.llm.tokens", "worker", "triage-agent", "type", "completion").count())
                .isEqualTo(50.0);
    }

    @Test
    void recordsPromptCacheTokensSeparatelyFromPlainInput() {
        metrics.recordLlmTokens("cypress-testcase-gen-agent",
                Map.of("promptTokens", 300L, "completionTokens", 40L, "cacheWriteTokens", 18000L));
        metrics.recordLlmTokens("cypress-testcase-gen-agent",
                Map.of("promptTokens", 300L, "completionTokens", 40L, "cacheReadTokens", 18000L));

        assertThat(registry.counter("factory.llm.tokens",
                "worker", "cypress-testcase-gen-agent", "type", "cache_write").count()).isEqualTo(18000);
        assertThat(registry.counter("factory.llm.tokens",
                "worker", "cypress-testcase-gen-agent", "type", "cache_read").count()).isEqualTo(18000);
    }

    @Test
    void ignoresMetricsWithoutTokenKeys() {
        metrics.recordLlmTokens("echo-worker", Map.of("fake", true));
        metrics.recordLlmTokens("echo-worker", Map.of());

        assertThat(registry.find("factory.llm.tokens").counters()).isEmpty();
    }
}
