package io.github.nikitaaovramenko.mineai;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

// The providers /ai can talk to. Each one owns its config option names so error messages can
// point at the right key. Minecraft-free on purpose, like the clients it dispatches to.
enum AiProvider {
    OPENAI("openai", "OpenAI", "openaiApiKey", "openaiModel", "gpt-4.1-mini"),
    ANTHROPIC("anthropic", "Anthropic", "anthropicApiKey", "anthropicModel", "claude-opus-5"),
    // SPIKE: same credentials and model as ANTHROPIC, routed through LangChain4j instead.
    ANTHROPIC_LANGCHAIN4J("anthropic-lc4j", "Anthropic via LangChain4j", "anthropicApiKey", "anthropicModel", "claude-opus-5");

    private final String id;
    private final String displayName;
    private final String apiKeyOption;
    private final String modelOption;
    private final String defaultModel;

    AiProvider(String id, String displayName, String apiKeyOption, String modelOption, String defaultModel) {
        this.id = id;
        this.displayName = displayName;
        this.apiKeyOption = apiKeyOption;
        this.modelOption = modelOption;
        this.defaultModel = defaultModel;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    String apiKeyOption() {
        return apiKeyOption;
    }

    String modelOption() {
        return modelOption;
    }

    String defaultModel() {
        return defaultModel;
    }

    // anthropicWorkspaceId is Anthropic-only and may be blank; the OpenAI branch ignores it.
    CompletableFuture<String> ask(String apiKey, String model, String prompt, String anthropicWorkspaceId) {
        return switch (this) {
            case OPENAI -> OpenAiClient.ask(apiKey, model, prompt);
            case ANTHROPIC -> AnthropicClient.ask(apiKey, anthropicWorkspaceId, model, prompt);
            case ANTHROPIC_LANGCHAIN4J -> LangChain4jClient.ask(apiKey, anthropicWorkspaceId, model, prompt);
        };
    }

    static Optional<AiProvider> byId(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.id.equals(wanted)).findFirst();
    }

    // Used in config comments and in the error shown when the configured id is unknown.
    static String ids() {
        return Arrays.stream(values()).map(AiProvider::id).collect(Collectors.joining(", "));
    }
}
