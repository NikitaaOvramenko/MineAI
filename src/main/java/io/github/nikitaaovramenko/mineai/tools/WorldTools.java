package io.github.nikitaaovramenko.mineai.tools;

import java.util.Optional;

import com.mojang.datafixers.util.Pair;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

// Tools about the world the asking player is in.
public class WorldTools {
    // How far vanilla /locate searches.
    private static final int SEARCH_RADIUS_CHUNKS = 100;

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

    @Tool("Finds the structure of a given kind nearest to the player, like the /locate command. Use it when the"
            + " player asks where the nearest village, stronghold, temple or other structure is.")
    public String findNearestStructure(@P("A structure id such as minecraft:stronghold, or a structure tag starting"
            + " with # such as #minecraft:village for any kind of village") String structure) {
        // /locate needs operator rights, which in single-player means cheats are on.
        if (!context.canUseCommand("locate")) {
            throw new IllegalStateException("This player is not allowed to locate structures (/locate is restricted).");
        }
        ServerPlayer player = context.player();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        // The search /locate runs. It blocks the server thread and can take a moment for rare structures.
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, structures(level, structure), origin, SEARCH_RADIUS_CHUNKS, false);
        String dimension = level.dimension().location().toString();
        if (found == null) {
            return "No " + structure + " within " + SEARCH_RADIUS_CHUNKS + " chunks of the player in " + dimension + ".";
        }
        BlockPos position = found.getFirst();
        String name = found.getSecond().unwrapKey().map(key -> key.location().toString()).orElse(structure);
        long distance = Math.round(Math.hypot(position.getX() - origin.getX(), position.getZ() - origin.getZ()));
        // Structure positions have no meaningful height, so only x and z, as /locate shows them.
        return "Nearest " + name + " in " + dimension + ": x=" + position.getX() + ", z=" + position.getZ()
                + ", about " + distance + " blocks away. The player is at x=" + origin.getX() + ", y="
                + origin.getY() + ", z=" + origin.getZ() + ".";
    }

    // As in /locate, a name starting with # is a structure tag. A plain name that is not a structure also
    // tries the tag of that name, so minecraft:village finds every kind of village.
    private static HolderSet<Structure> structures(ServerLevel level, String name) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        boolean isTag = name.startsWith("#");
        ResourceLocation id = ResourceLocation.parse(isTag ? name.substring(1) : name);
        Optional<Holder.Reference<Structure>> single = isTag
                ? Optional.empty()
                : registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (single.isPresent()) {
            return HolderSet.direct(single.get());
        }
        return registry.getTag(TagKey.create(Registries.STRUCTURE, id)).orElseThrow(
                () -> new IllegalArgumentException("There is no structure or structure tag called " + name + "."));
    }
}
