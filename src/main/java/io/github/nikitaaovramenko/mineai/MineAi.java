package io.github.nikitaaovramenko.mineai;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;

import io.github.nikitaaovramenko.mineai.tools.PlacedContainers;
import io.github.nikitaaovramenko.mineai.tools.ToolContext;
import io.github.nikitaaovramenko.mineai.tools.ToolRegistry;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(MineAi.MODID)
public class MineAi {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "mineai";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    private final Set<UUID> pendingPrompts = ConcurrentHashMap.newKeySet();
    // Create a Deferred Register to hold Blocks which will all be registered under the "mineai" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "mineai" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "mineai" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "mineai:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "mineai:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "mineai:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "mineai:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mineai")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get());// Add the example item to the tab. For your own tabs, this method is preferred over the event
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public MineAi(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        // The attachment that marks containers players placed, for StorageTools.
        PlacedContainers.ATTACHMENT_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (MineAi) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ai")
                .executes(context -> {
                    context.getSource().sendFailure(Component.literal("Usage: /ai <prompt>"));
                    return 0;
                })
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(context -> {
                            String prompt = StringArgumentType.getString(context, "prompt");
                            return askAi(context.getSource(), prompt);
                        })));
    }

    private int askAi(CommandSourceStack source, String prompt) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        AiProvider provider = AiProvider.byId(Config.AI_PROVIDER.get()).orElse(null);
        if (provider == null) {
            source.sendFailure(Component.literal("[MineAi] Unknown provider in config/mineai-common.toml. Use one of: "
                    + AiProvider.ids() + "."));
            return 0;
        }
        String apiKey = Config.apiKey(provider).get().trim();
        String model = Config.model(provider).get().trim();
        if (apiKey.isEmpty() || model.isEmpty()) {
            source.sendFailure(Component.literal("[MineAi] Set " + provider.apiKeyOption() + " and "
                    + provider.modelOption() + " in config/mineai-common.toml first."));
            return 0;
        }
        if (prompt.isBlank()) {
            source.sendFailure(Component.literal("Usage: /ai <prompt>"));
            return 0;
        }
        UUID playerId = player.getUUID();
        if (!pendingPrompts.add(playerId)) {
            source.sendFailure(Component.literal("[MineAi] Please wait for your current answer."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[MineAi] Thinking..."), false);
        var server = source.getServer();
        List<Object> tools = ToolRegistry.create(new ToolContext(server, playerId));
        // Tools touch the world, so they run on the server thread. executeIfPossible refuses once the
        // server has stopped, where execute would run the task on the calling network thread instead.
        provider.ask(apiKey, model, prompt, Config.ANTHROPIC_WORKSPACE_ID.get().trim(), tools,
                server::executeIfPossible).whenComplete((answer, error) -> {
            pendingPrompts.remove(playerId);
            if (server.isStopped()) {
                return;
            }
            // Network callbacks run off-thread; all player access belongs on the server thread.
            server.execute(() -> {
                if (server.getPlayerList().getPlayer(playerId) != player) {
                    return;
                }
                if (error == null) {
                    player.sendSystemMessage(Component.literal("[MineAi] " + answer));
                } else {
                    Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                    String message;
                    if (cause instanceof RequestException failure) {
                        message = failure.getMessage();
                        // The provider explanation is unsafe for chat but is what an admin needs.
                        LOGGER.warn("{} request failed: {}", provider.displayName(), failure.detail());
                    } else {
                        message = "Could not reach " + provider.displayName() + " or the request timed out. Please try again.";
                        LOGGER.warn("{} request failed", provider.displayName(), cause);
                    }
                    source.sendFailure(Component.literal("[MineAi] " + message));
                }
            });
        });
        return 1;
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
