package io.github.nikitaaovramenko.mineai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class AnthropicClient {
    // Current Claude models think adaptively by default and thinking shares this budget with the
    // reply, so it sits well above the OpenAI one to leave room for actual text.
    private static final int MAX_OUTPUT_TOKENS = 4000;
    // Thinking also costs wall time; a chat command still has to give up eventually.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private AnthropicClient() {}

    static CompletableFuture<String> ask(String apiKey, String workspaceId, String model, String prompt) {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", prompt);
            JsonArray messages = new JsonArray();
            messages.add(message);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
            body.addProperty("system", AiClients.SYSTEM_PROMPT);
            body.add("messages", messages);

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            // Org-scoped keys must name a workspace; workspace-scoped keys must not.
            if (!workspaceId.isBlank()) {
                request.header("anthropic-workspace-id", workspaceId);
            }
            return AiClients.HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(response.statusCode(), response.body()));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new RequestException("Check your Anthropic API key configuration."));
        }
    }

    static String parseResponse(int status, String body) {
        if (status < 200 || status >= 300) {
            throw AiClients.httpFailure(AiProvider.ANTHROPIC, status, body);
        }

        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            String stopReason = response.has("stop_reason") && !response.get("stop_reason").isJsonNull()
                    ? response.get("stop_reason").getAsString()
                    : "";
            StringJoiner text = new StringJoiner("\n");
            for (JsonElement item : response.getAsJsonArray("content")) {
                JsonObject block = item.getAsJsonObject();
                // Thinking blocks are interleaved with the answer and come back empty unless the
                // request asks for a summary, so only text blocks are worth showing.
                if ("text".equals(block.get("type").getAsString())) {
                    text.add(block.get("text").getAsString());
                }
            }
            if (text.toString().isBlank()) {
                // A declined request is a successful HTTP 200 with no content at all, so the stop
                // reason is the only thing that explains the empty answer.
                throw new RequestException(switch (stopReason) {
                    case "refusal" -> "Claude declined to answer that prompt.";
                    case "max_tokens" -> "Claude used its whole output budget before replying. Try a shorter question.";
                    default -> "Claude returned no text. Try another prompt or model.";
                });
            }
            if ("max_tokens".equals(stopReason)) {
                text.add(AiClients.TRUNCATION_NOTE);
            }
            return text.toString();
        } catch (RequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RequestException("Claude returned an unreadable response. Try again later.");
        }
    }
}
