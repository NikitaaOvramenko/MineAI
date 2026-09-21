package io.github.nikitaaovramenko.mineai;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

// SPIKE: the same Anthropic call as AnthropicClient, but through LangChain4j, to find out what the
// library costs and whether it survives NeoForge class loading. Reuses the anthropic* config keys.
final class LangChain4jClient {
    private static final int MAX_OUTPUT_TOKENS = 4000;

    private LangChain4jClient() {}

    static CompletableFuture<String> ask(String apiKey, String workspaceId, String model, String prompt) {
        ChatModel chat = buildModel(apiKey, workspaceId, model);
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(AiClients.SYSTEM_PROMPT), UserMessage.from(prompt))
                .build();
        return chat.chatAsync(request).handle((response, error) -> {
            if (error != null) {
                // The library reports failures as its own exception types, so the curated
                // per-status messages in AiClients do not apply here. Keep the library text out of
                // chat (it can quote the response body) and hand it to the log instead.
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                throw new RequestException("The LangChain4j provider failed. Check the server log for details.",
                        cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) {
                throw new RequestException("Claude returned no text. Try another prompt or model.");
            }
            return text;
        });
    }

    private static ChatModel buildModel(String apiKey, String workspaceId, String model) {
        // One model object per request. A real implementation would cache this per config value:
        // each instance builds its own HTTP client.
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxTokens(MAX_OUTPUT_TOKENS);
        if (!workspaceId.isBlank()) {
            builder.customHeaders(Map.of("anthropic-workspace-id", workspaceId));
        }
        return builder.build();
    }
}
