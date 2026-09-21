package io.github.nikitaaovramenko.mineai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class OpenAiClient {
    private static final int MAX_OUTPUT_TOKENS = 800;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private OpenAiClient() {}

    static CompletableFuture<String> ask(String apiKey, String model, String prompt) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("input", prompt);
            body.addProperty("instructions", AiClients.SYSTEM_PROMPT);
            body.addProperty("max_output_tokens", MAX_OUTPUT_TOKENS);
            body.addProperty("store", false);

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return AiClients.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(response.statusCode(), response.body()));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new RequestException("Check your OpenAI API key configuration."));
        }
    }

    static String parseResponse(int status, String body) {
        if (status < 200 || status >= 300) {
            throw AiClients.httpFailure(AiProvider.OPENAI, status, body);
        }

        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            StringJoiner text = new StringJoiner("\n");
            for (JsonElement item : response.getAsJsonArray("output")) {
                JsonObject output = item.getAsJsonObject();
                if (!"message".equals(output.get("type").getAsString())) {
                    continue;
                }
                for (JsonElement part : output.getAsJsonArray("content")) {
                    JsonObject content = part.getAsJsonObject();
                    String type = content.get("type").getAsString();
                    if ("output_text".equals(type)) {
                        text.add(content.get("text").getAsString());
                    } else if ("refusal".equals(type)) {
                        text.add(content.get("refusal").getAsString());
                    }
                }
            }
            if (text.toString().isBlank()) {
                throw new RequestException("OpenAI returned no text. Try another prompt or model.");
            }
            if (response.has("status") && "incomplete".equals(response.get("status").getAsString())) {
                text.add(AiClients.TRUNCATION_NOTE);
            }
            return text.toString();
        } catch (RequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RequestException("OpenAI returned an unreadable response. Try again later.");
        }
    }
}
