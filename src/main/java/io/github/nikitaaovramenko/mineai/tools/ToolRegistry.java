package io.github.nikitaaovramenko.mineai.tools;

import java.util.List;

// The tools /ai offers the model: objects whose methods carry LangChain4j's @Tool annotation. To add
// one, write a class like WorldTools in this package and list it here. A tool's return value is sent
// to the model as text (JSON for non-strings); a thrown exception's message goes back as an error.
// Only the LangChain4j providers (anthropic-lc4j and google) call tools; the plain HTTP ones ignore them.
public final class ToolRegistry {
    private ToolRegistry() {}

    public static List<Object> create(ToolContext context) {
        return List.of(new WorldTools(context), new StorageTools(context), new PlayerActions(context));
    }
}
