package io.github.nikitaaovramenko.mineai.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AsyncNotSupportedException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.FinishReason;

// The providers that go through LangChain4j instead of plain HTTP: Anthropic (a SPIKE, beside
// AnthropicClient) and Google's Gemini. Unlike the plain clients they let the model call tools (see the
// tools package), and the tool loop in converse is the same whichever model answers.
final class LangChain4jClient {
    private static final int MAX_OUTPUT_TOKENS = 4000;
    // Room for a few lookups per prompt; a model that keeps calling tools is cut off.
    private static final int MAX_TOOL_ROUNDS = 5;

    private LangChain4jClient() {}

    static CompletableFuture<String> ask(AiProvider provider, Supplier<ChatModel> model, String prompt,
            List<Object> tools, Executor toolExecutor) {
        CompletableFuture<String> answer;
        try {
            List<ChatMessage> messages = new ArrayList<>(
                    List.of(SystemMessage.from(AiClients.SYSTEM_PROMPT), UserMessage.from(prompt)));
            answer = converse(provider, model.get(), messages, new LangChain4jTools(tools, toolExecutor),
                    MAX_TOOL_ROUNDS);
        } catch (RuntimeException exception) {
            // Fail the future rather than throw: the caller only clears its pending entry on completion.
            answer = CompletableFuture.failedFuture(exception);
        }
        return answer.handle((text, error) -> {
            if (error == null) {
                return text;
            }
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            throw cause instanceof RequestException failure ? failure : failure(provider, cause);
        });
    }

    // The library throws its own exception types. One caused by an HTTP error gets the same fixed message
    // for its status as the plain clients give; anything else only says where to look. The library's text
    // stays out of chat either way, as it can quote the response body, and goes to the log instead.
    static RequestException failure(AiProvider provider, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpException http) {
                return AiClients.httpFailure(provider, http.statusCode(), http.getMessage());
            }
        }
        return new RequestException(provider.displayName() + " failed. Check the server log for details.",
                error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    // One model call. If the model stopped to use tools, run them, append the results and go again.
    static CompletableFuture<String> converse(AiProvider provider, ChatModel chat, List<ChatMessage> messages,
            LangChain4jTools tools, int toolRoundsLeft) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(tools.specifications())
                .build();
        return call(chat, request).thenCompose(response -> {
            AiMessage reply = response.aiMessage();
            // Only a turn that stopped for tool use runs them: a truncated turn can end mid-call.
            if (response.finishReason() != FinishReason.TOOL_EXECUTION || !reply.hasToolExecutionRequests()) {
                return CompletableFuture.completedFuture(answer(provider, reply, response.finishReason()));
            }
            if (toolRoundsLeft == 0) {
                throw new RequestException(provider.displayName()
                        + " kept calling tools without answering. Try a simpler prompt.");
            }
            messages.add(reply);
            // The results arrive on the tool executor (the server thread); build the next request elsewhere.
            return tools.executeAll(reply.toolExecutionRequests()).thenComposeAsync(results -> {
                messages.addAll(results);
                return converse(provider, chat, messages, tools, toolRoundsLeft - 1);
            });
        });
    }

    // Anthropic's model answers asynchronously. Gemini's has only the blocking call in 1.20.0, which then
    // waits on a virtual thread of its own rather than tying up a server or pool thread.
    private static CompletableFuture<ChatResponse> call(ChatModel chat, ChatRequest request) {
        return chat.chatAsync(request).exceptionallyCompose(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            return cause instanceof AsyncNotSupportedException
                    ? CompletableFuture.supplyAsync(() -> chat.chat(request),
                            task -> Thread.ofVirtual().name("mineai-model-call").start(task))
                    : CompletableFuture.failedFuture(error);
        });
    }

    // Blank text is explained by why the model stopped, as the plain clients do.
    private static String answer(AiProvider provider, AiMessage reply, FinishReason finishReason) {
        if (reply.text() == null || reply.text().isBlank()) {
            throw new RequestException(finishReason == FinishReason.CONTENT_FILTER
                    ? provider.displayName() + " declined to answer that. Try rephrasing it."
                    : provider.displayName() + " returned no text. Try another prompt or model.");
        }
        return finishReason == FinishReason.LENGTH ? reply.text() + "\n" + AiClients.TRUNCATION_NOTE : reply.text();
    }

    // One model object per request. A real implementation would cache these per config value:
    // each instance builds its own HTTP client.

    static ChatModel anthropic(String apiKey, String workspaceId, String model) {
        // returnThinking stays off, so a replayed tool-call turn goes back without its thinking
        // blocks. Adaptive-thinking models accept that. Turning it on is worse in 1.20.0: its mapper
        // still drops the empty blocks Claude returns by default, and splices several blocks'
        // signatures into one.
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxTokens(MAX_OUTPUT_TOKENS);
        if (!workspaceId.isBlank()) {
            builder.customHeaders(Map.of("anthropic-workspace-id", workspaceId));
        }
        return builder.build();
    }

    static ChatModel google(String apiKey, String model) {
        return google(apiKey, model, null);
    }

    // A null baseUrl means Google's own; tests point it at a local stand-in.
    static ChatModel google(String apiKey, String model, String baseUrl) {
        // Gemini's function calls carry thought signatures that must come back unchanged in the next
        // request, or newer models reject it. returnThinking keeps them on the AiMessage and sendThinking
        // sends them back. Neither asks for thought text, so the reply stays free of it.
        return GoogleAiGeminiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }
}
