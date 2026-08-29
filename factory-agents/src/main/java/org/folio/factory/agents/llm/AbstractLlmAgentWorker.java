package org.folio.factory.agents.llm;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for LLM-backed agent workers. Provider-agnostic: workers only see
 * the Spring AI {@link ChatClient}; the concrete model (Anthropic, OpenAI, …) is
 * chosen by configuration. Prompts live at
 * {@code classpath:prompts/{workerId}/system.md} and {@code user.md}.
 *
 * <p>Includes one worker-internal retry for malformed structured output. This is
 * distinct from the engine's step retry budget: a parse retry re-asks the model
 * within the same step attempt.</p>
 */
public abstract class AbstractLlmAgentWorker implements AgentWorker {

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmAgentWorker.class);

    // Token usage of the last successful model call on this thread. Set per call
    // (never accumulated) and cleared both at callForEntity entry and on read, so
    // a reused engine pool thread cannot leak counts into a later step even when
    // a worker throws after its call. A worker making several calls per execute
    // reports only the last one.
    private static final ThreadLocal<TokenUsage> USAGE = new ThreadLocal<>();

    /**
     * One model call's token counts. {@code cacheWrite}/{@code cacheRead} are populated only
     * by providers that support prompt caching and report it; they are zero everywhere else,
     * so reading them never depends on which provider is configured.
     */
    public record TokenUsage(long prompt, long completion, long cacheWrite, long cacheRead) {

        public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

        public TokenUsage plus(TokenUsage other) {
            return new TokenUsage(prompt + other.prompt, completion + other.completion,
                    cacheWrite + other.cacheWrite, cacheRead + other.cacheRead);
        }

        public boolean isEmpty() {
            return prompt + completion + cacheWrite + cacheRead == 0;
        }

        /** Metric map for {@link AgentResult}; cache entries are omitted when nothing was cached. */
        public Map<String, Object> toMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("promptTokens", prompt);
            metrics.put("completionTokens", completion);
            if (cacheWrite > 0) metrics.put("cacheWriteTokens", cacheWrite);
            if (cacheRead > 0) metrics.put("cacheReadTokens", cacheRead);
            return metrics;
        }
    }

    protected final ChatClient chatClient;

    protected AbstractLlmAgentWorker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    protected String systemPrompt(Map<String, Object> variables) {
        return PromptLoader.render(PromptLoader.load(id(), "system"), variables);
    }

    protected String userPrompt(Map<String, Object> variables) {
        return PromptLoader.render(PromptLoader.load(id(), "user"), variables);
    }

    /**
     * Override to request a larger output window for workers that generate long structured output.
     * Returns {@code null} by default (use the model's or global default).
     */
    protected Integer maxTokens() {
        return null;
    }

    /**
     * Options applied to every model call this worker makes, or {@code null} for the
     * configured defaults. The default implementation honours {@link #maxTokens()}, so
     * overriding that alone keeps working.
     *
     * <p>Override this instead when a worker needs provider-specific options — prompt
     * caching, say. Returning a provider-specific subtype is safe: a model from another
     * provider reads only the portable {@link ChatOptions} properties and logs a warning
     * for the rest, so the worker degrades rather than failing. Keep the provider
     * dependency in the flow module; this class stays provider-neutral.</p>
     */
    protected ChatOptions.Builder<?> chatOptions() {
        Integer tokens = maxTokens();
        return tokens == null ? null : ChatOptions.builder().maxTokens(tokens);
    }

    /**
     * Calls the model and binds the response to {@code type} via Spring AI's
     * structured output support, retrying once on unparseable output.
     */
    protected <T> T callForEntity(Map<String, Object> variables, Class<T> type) {
        return callForEntity(id(), variables, type);
    }

    /**
     * Like {@link #callForEntity(Map, Class)} but loads prompts from
     * {@code prompts/{promptId}/} instead of the worker's own prompt directory.
     * Useful for workers that make multiple structured-output calls with different
     * prompt sets in a single {@code execute()}.
     */
    protected <T> T callForEntity(String promptId, Map<String, Object> variables, Class<T> type) {
        USAGE.remove();
        String system = PromptLoader.render(PromptLoader.load(promptId, "system"), variables);
        String user = PromptLoader.render(PromptLoader.load(promptId, "user"), variables);
        try {
            return captureUsage(buildPrompt(system, user).call().responseEntity(type));
        } catch (JacksonException firstFailure) {
            // JacksonException is what BeanOutputConverter propagates on unparseable
            // output; transport/auth/rate-limit failures must fall through to the
            // engine's step retry instead of triggering a second model call here.
            log.warn("Worker '{}' got unparseable structured output ({}); retrying once",
                    id(), firstFailure.getMessage());
            try {
                return captureUsage(buildPrompt(system,
                        user + "\n\nIMPORTANT: your previous response could not be parsed. "
                                + "Respond with ONLY the requested JSON — no prose, no code fences.")
                        .call()
                        .responseEntity(type));
            } catch (JacksonException secondFailure) {
                throw new AgentExecutionException("Worker '" + id() + "' produced unparseable output twice: "
                        + secondFailure.getMessage(), secondFailure);
            }
        }
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(String system, String user) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        ChatOptions.Builder<?> options = chatOptions();
        if (options != null) {
            spec = spec.options(options);
        }
        return spec.system(system).user(user);
    }

    private <T> T captureUsage(ResponseEntity<ChatResponse, T> result) {
        captureUsageFromResponse(result.response());
        return result.entity();
    }

    private void captureUsageFromResponse(ChatResponse response) {
        if (response == null) return;
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            // getCacheReadInputTokens/getCacheWriteInputTokens are default methods on the
            // portable Usage interface, so reading them keeps this class provider-neutral;
            // providers without prompt caching return null and report zero.
            USAGE.set(new TokenUsage(
                    zeroIfNull(usage.getPromptTokens()),
                    zeroIfNull(usage.getCompletionTokens()),
                    zeroIfNull(usage.getCacheWriteInputTokens()),
                    zeroIfNull(usage.getCacheReadInputTokens())));
        }
    }

    private static long zeroIfNull(Number value) {
        return value == null ? 0 : value.longValue();
    }

    /**
     * Reads and clears the current call's token usage. Workers making multiple LLM
     * calls per {@code execute()} call this after each call to bank tokens before
     * the next {@code callForEntity} entry resets the ThreadLocal.
     */
    protected TokenUsage drainUsage() {
        TokenUsage usage = USAGE.get();
        USAGE.remove();
        return usage == null ? TokenUsage.ZERO : usage;
    }

    /**
     * Builds the step result carrying the last model call's token usage as
     * {@code promptTokens}/{@code completionTokens} metrics (plus cache counts when the
     * provider reports them). Failed attempts (including the internal parse-retry's first
     * attempt) are not observable through the structured-output API and are not counted.
     */
    protected AgentResult resultWithUsage(String artifactName, String content) {
        TokenUsage usage = drainUsage();
        if (usage.isEmpty()) {
            return AgentResult.of(artifactName, content);
        }
        return new AgentResult(Map.of(artifactName, content), usage.toMetrics());
    }

    protected String callForText(Map<String, Object> variables) {
        return buildPrompt(systemPrompt(variables), userPrompt(variables)).call().content();
    }

    /**
     * Calls the model using prompts from {@code prompts/{promptId}/system.md} and
     * {@code user.md}, captures token usage to the {@link #USAGE} ThreadLocal so
     * the caller can bank it with {@link #drainUsage()}.
     */
    protected String callForText(String promptId, Map<String, Object> variables) {
        USAGE.remove();
        String system = PromptLoader.render(PromptLoader.load(promptId, "system"), variables);
        String user = PromptLoader.render(PromptLoader.load(promptId, "user"), variables);
        ChatResponse response = buildPrompt(system, user).call().chatResponse();
        captureUsageFromResponse(response);
        return response != null && response.getResult() != null
                ? response.getResult().getOutput().getText()
                : "";
    }
}
