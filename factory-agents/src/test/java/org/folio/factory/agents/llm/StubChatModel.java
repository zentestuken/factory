package org.folio.factory.agents.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted ChatModel: returns queued responses in order and records prompts.
 */
public class StubChatModel implements ChatModel {

    private record QueuedResponse(String text, int promptTokens, int completionTokens,
                                  Long cacheWriteTokens, Long cacheReadTokens) {
    }

    private final List<QueuedResponse> queuedResponses = new ArrayList<>();
    private final List<Prompt> receivedPrompts = new ArrayList<>();

    public StubChatModel enqueue(String response) {
        return enqueue(response, 0, 0);
    }

    /** Queues a response that reports token usage in its metadata. */
    public StubChatModel enqueue(String response, int promptTokens, int completionTokens) {
        queuedResponses.add(new QueuedResponse(response, promptTokens, completionTokens, null, null));
        return this;
    }

    /** Queues a response that also reports prompt-cache token counts, as Anthropic does. */
    public StubChatModel enqueueCached(String response, int promptTokens, int completionTokens,
                                       long cacheWriteTokens, long cacheReadTokens) {
        queuedResponses.add(new QueuedResponse(response, promptTokens, completionTokens,
                cacheWriteTokens, cacheReadTokens));
        return this;
    }

    public List<Prompt> receivedPrompts() {
        return receivedPrompts;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        receivedPrompts.add(prompt);
        if (queuedResponses.isEmpty()) {
            throw new IllegalStateException("StubChatModel has no queued response");
        }
        QueuedResponse next = queuedResponses.removeFirst();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(next.text()))),
                ChatResponseMetadata.builder()
                        // (prompt, completion, total, nativeUsage, cacheRead, cacheWrite)
                        .usage(new DefaultUsage(next.promptTokens(), next.completionTokens(), null,
                                null, next.cacheReadTokens(), next.cacheWriteTokens()))
                        .build());
    }
}
