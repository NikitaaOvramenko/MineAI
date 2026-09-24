package io.github.nikitaaovramenko.mineai.providers;

import java.net.http.HttpClient;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Shared plumbing for the provider clients. Minecraft-free on purpose: everything here is
// reachable from plain unit tests.
final class AiClients {
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    static final String SYSTEM_PROMPT = "Reply concisely in plain text suitable for Minecraft chat."
            + " When tools are available and the player asks to find, locate, or show items in storage,"
            + " call findItemInStorage, then call highlightStorage for each matching storage position returned."
            + " Send all matching highlightStorage calls together in one tool round."
            + " Highlight automatically without asking for confirmation, unless the player asks not to highlight."
            + " Only use positions returned by the tools, and only say storage was highlighted after the"
            + " highlightStorage calls succeed. If nothing matches, report that without highlighting.";
    static final String TRUNCATION_NOTE = "[Response reached its output limit.]";

    private AiClients() {}

    // Never display raw API errors to a player: they can contain credential or request details.
    // The provider's explanation is attached as detail so the server log can still show it.
    static RequestException httpFailure(AiProvider provider, int status, String body) {
        String name = provider.displayName();
        // Google answers a bad key with 400 rather than 401. The body only decides, it is never shown.
        boolean badKey = status == 400 && body != null && body.contains("API_KEY_INVALID");
        String message = switch (badKey ? 401 : status) {
            case 401 -> name + " rejected the API key. Check " + provider.apiKeyOption() + " in the config.";
            case 403 -> name + " denied access. Check your project and model permissions.";
            case 404 -> name + " does not know that model. Check " + provider.modelOption() + " in the config.";
            case 429 -> name + " quota or rate limit reached. Check billing or try again later.";
            case 400 -> name + " rejected the request. Check " + provider.modelOption()
                    + " in the config, and the server log for details.";
            case 500, 502, 503, 529 -> name + " is temporarily unavailable. Try again later.";
            default -> name + " request failed (HTTP " + status + "). Try again later.";
        };
        return new RequestException(message, "HTTP " + status + " " + errorSummary(body));
    }

    // OpenAI and Anthropic report failures as {"error": {"type": ..., "message": ...}}; Google has a
    // "status" such as INVALID_ARGUMENT where they have "type".
    private static String errorSummary(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            String type = (error.has("type") ? error.get("type") : error.get("status")).getAsString();
            return type + ": " + error.get("message").getAsString();
        } catch (RuntimeException exception) {
            return "(no error details in response)";
        }
    }
}
