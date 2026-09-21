package io.github.nikitaaovramenko.mineai.tools;

import dev.langchain4j.agent.tool.Tool;

// Tools about the world the asking player is in.
public class WorldTools {
    private final ToolContext context;

    public WorldTools(ToolContext context) {
        this.context = context;
    }

    @Tool("Returns the seed of the Minecraft world the player is in. Use it whenever the player asks for"
            + " the seed or asks something that depends on it.")
    public long getWorldSeed() {
        // A dedicated server only shows /seed to operators; /ai must not be a way around that.
        if (!context.canUseCommand("seed")) {
            throw new IllegalStateException("This player is not allowed to see the world seed (/seed is restricted).");
        }
        return context.server().getWorldData().worldGenOptions().seed();
    }
}
