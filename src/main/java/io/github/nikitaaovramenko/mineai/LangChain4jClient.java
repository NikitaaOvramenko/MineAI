package io.github.nikitaaovramenko.mineai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.FinishReason;

// SPIKE: the same Anthropic call as AnthropicClient, but through LangChain4j, to find out what the
// library costs and whether it survives NeoForge class loading. Reuses the anthropic* config keys.
// Unlike the plain clients it lets the model call tools (see the tools package).
final class LangChain4jClient {
    // Thinking and the reply share this budget, and the reply may be a whole building (BuildTools.planBuild).
    // converse drops a tool call cut off by it rather than running half a blueprint.
    private static final int MAX_OUTPUT_TOKENS = 16000;
    // LangChain4j's default read timeout is 60s, too short for thinking through a blueprint.
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    // Room for a few lookups per prompt; a model that keeps calling tools is cut off.
    private static final int MAX_TOOL_ROUNDS = 5;

    private LangChain4jClient() {}

    static CompletableFuture<String> ask(String apiKey, String workspaceId, String model, String prompt,
            List<Object> tools, Executor toolExecutor) {
        CompletableFuture<String> answer;
        try {
            List<ChatMessage> messages = new ArrayList<>(
                    List.of(SystemMessage.from(AiClients.SYSTEM_PROMPT), UserMessage.from(prompt)));
            answer = converse(buildModel(apiKey, workspaceId, model), messages,
                    new LangChain4jTools(tools, toolExecutor), MAX_TOOL_ROUNDS);
        } catch (RuntimeException exception) {
            // Fail the future rather than throw: the caller only clears its pending entry on completion.
            answer = CompletableFuture.failedFuture(exception);
        }
        return answer.handle((text, error) -> {
            if (error == null) {
                return text;
            }
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            if (cause instanceof RequestException failure) {
                throw failure;
            }
            // The library reports failures as its own exception types, so the curated
            // per-status messages in AiClients do not apply here. Keep the library text out of
            // chat (it can quote the response body) and hand it to the log instead.
            throw new RequestException("The LangChain4j provider failed. Check the server log for details.",
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        });
    }

    // One model call. If the model stopped to use tools, run them, append the results and go again.
    static CompletableFuture<String> converse(ChatModel chat, List<ChatMessage> messages, LangChain4jTools tools,
            int toolRoundsLeft) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(tools.specifications())
                .build();
        return chat.chatAsync(request).thenCompose(response -> {
            AiMessage reply = response.aiMessage();
            // Only a turn that stopped for tool use runs them: a truncated turn can end mid-call.
            if (response.finishReason() != FinishReason.TOOL_EXECUTION || !reply.hasToolExecutionRequests()) {
                if (reply.text() == null || reply.text().isBlank()) {
                    throw new RequestException("Claude returned no text. Try another prompt or model.");
                }
                return CompletableFuture.completedFuture(reply.text());
            }
            if (toolRoundsLeft == 0) {
                throw new RequestException("Claude kept calling tools without answering. Try a simpler prompt.");
            }
            messages.add(reply);
            // The results arrive on the tool executor (the server thread); build the next request elsewhere.
            return tools.executeAll(reply.toolExecutionRequests()).thenComposeAsync(results -> {
                messages.addAll(results);
                return converse(chat, messages, tools, toolRoundsLeft - 1);
            });
        });
    }

    private static ChatModel buildModel(String apiKey, String workspaceId, String model) {
        // One model object per request. A real implementation would cache this per config value:
        // each instance builds its own HTTP client.
        // returnThinking stays off, so a replayed tool-call turn goes back without its thinking
        // blocks. Adaptive-thinking models accept that. Turning it on is worse in 1.20.0: its mapper
        // still drops the empty blocks Claude returns by default, and splices several blocks'
        // signatures into one.
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .timeout(TIMEOUT);
        if (!workspaceId.isBlank()) {
            builder.customHeaders(Map.of("anthropic-workspace-id", workspaceId));
        }
        return builder.build();
    }
}
