package io.github.nikitaaovramenko.mineai;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    public static final ModConfigSpec.ConfigValue<String> AI_PROVIDER = BUILDER
            .comment("Which provider answers /ai. One of: " + AiProvider.ids() + ".",
                    "Only the key and model for the selected provider are used.")
            .define("provider", AiProvider.ANTHROPIC.id(), Config::validateProvider);

    public static final ModConfigSpec.ConfigValue<String> OPENAI_API_KEY = BUILDER
            .comment("OpenAI API key. Keep this file private. Used only on the server (or in single-player).")
            .define("openaiApiKey", "");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_MODEL = BUILDER
            .comment("OpenAI model used for /ai prompts.")
            .define("openaiModel", AiProvider.OPENAI.defaultModel());

    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_API_KEY = BUILDER
            .comment("Anthropic API key. Keep this file private. Used only on the server (or in single-player).")
            .define("anthropicApiKey", "");

    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_WORKSPACE_ID = BUILDER
            .comment("Optional Anthropic workspace id (looks like wrkspc_...).",
                    "Required only when the API key is not scoped to a workspace; leave blank otherwise.")
            .define("anthropicWorkspaceId", "");

    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_MODEL = BUILDER
            .comment("Claude model used for /ai prompts. claude-haiku-4-5 is the cheaper option.")
            .define("anthropicModel", AiProvider.ANTHROPIC.defaultModel());

    static final ModConfigSpec SPEC = BUILDER.build();

    public static ModConfigSpec.ConfigValue<String> apiKey(AiProvider provider) {
        return switch (provider) {
            case OPENAI -> OPENAI_API_KEY;
            case ANTHROPIC, ANTHROPIC_LANGCHAIN4J -> ANTHROPIC_API_KEY;
        };
    }

    public static ModConfigSpec.ConfigValue<String> model(AiProvider provider) {
        return switch (provider) {
            case OPENAI -> OPENAI_MODEL;
            case ANTHROPIC, ANTHROPIC_LANGCHAIN4J -> ANTHROPIC_MODEL;
        };
    }

    private static boolean validateProvider(final Object obj) {
        return obj instanceof String id && AiProvider.byId(id).isPresent();
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
