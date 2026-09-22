package io.github.nikitaaovramenko.mineai.providers;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

// The providers /ai can talk to. Each one owns its config option names so error messages can
// point at the right key. Minecraft-free on purpose, like the clients it dispatches to.
public enum AiProvider {
    OPENAI("openai", "OpenAI", "openaiApiKey", "openaiModel", "gpt-4.1-mini"),
    ANTHROPIC("anthropic", "Anthropic", "anthropicApiKey", "anthropicModel", "claude-opus-5"),
    // SPIKE: same credentials and model as ANTHROPIC, routed through LangChain4j instead.
    ANTHROPIC_LANGCHAIN4J("anthropic-lc4j", "Anthropic via LangChain4j", "anthropicApiKey", "anthropicModel", "claude-opus-5"),
    // Gemini, with a key from Google AI Studio. Through LangChain4j, so it can call tools.
    GOOGLE("google", "Google AI Studio", "googleApiKey", "googleModel", "gemini-3.8-flash");

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

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String apiKeyOption() {
        return apiKeyOption;
    }

    public String modelOption() {
        return modelOption;
    }

    public String defaultModel() {
        return defaultModel;
    }

    // anthropicWorkspaceId is Anthropic-only and may be blank; the other providers ignore it.
    // Only the LangChain4j providers call tools, running them on toolExecutor. The parameters are
    // plain JDK types so the other providers work without LangChain4j on the classpath.
    public CompletableFuture<String> ask(String apiKey, String model, String prompt, String anthropicWorkspaceId,
            List<Object> tools, Executor toolExecutor) {
        return switch (this) {
            case OPENAI -> OpenAiClient.ask(apiKey, model, prompt);
            case ANTHROPIC -> AnthropicClient.ask(apiKey, anthropicWorkspaceId, model, prompt);
            case ANTHROPIC_LANGCHAIN4J -> LangChain4jClient.ask(this,
                    () -> LangChain4jClient.anthropic(apiKey, anthropicWorkspaceId, model),
                    prompt, tools, toolExecutor);
            case GOOGLE -> LangChain4jClient.ask(this,
                    () -> LangChain4jClient.google(apiKey, model), prompt, tools, toolExecutor);
        };
    }

    public static Optional<AiProvider> byId(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.id.equals(wanted)).findFirst();
    }

    // Used in config comments and in the error shown when the configured id is unknown.
    public static String ids() {
        return Arrays.stream(values()).map(AiProvider::id).collect(Collectors.joining(", "));
    }
}
