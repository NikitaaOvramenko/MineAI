package io.github.nikitaaovramenko.mineai.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

// Tools about the storage players have placed near the asking player (see PlacedContainers). No command
// covers this, so the tool stays within what the player could see by walking over: storage near them, in
// chunks that are already loaded. It doesn't know about vanilla locks or land-claim mods.
public class StorageTools {
    private static final int MAX_RADIUS = 64;

    private final ToolContext context;

    public StorageTools(ToolContext context) {
        this.context = context;
    }

    private record Found(double distance, String line) {}

    private record Storage(String kind, @Nullable Component name, BlockPos pos, List<ItemStack> stacks) {}

    public record StoragePosition(int x, int y, int z) {}

    @Tool("Lists the storage players placed near the player and everything in it: chests, barrels and shulker"
            + " boxes, minecarts and boats with chests, and donkeys, mules and llamas carrying chests. For each, its"
            + " kind, name and position, and each item's name and count. Storage the world generated is left out."
            + " Use it for a general storage overview. To locate a specific item, use findItemInStorage."
            + " To highlight a storage position, use highlightStorage.")
    public String listNearbyContainers(@P(value = "How far from the player to look, in blocks, up to 64",
            defaultValue = "32") int radius) {
        ServerPlayer player = context.player();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int range = Math.clamp(radius, 1, MAX_RADIUS);

        List<Found> found = new ArrayList<>();
        for (Storage storage : collectStorage(level, origin, range)) {
            found.add(entry(storage.kind(), storage.name(), storage.pos(), origin, storage.stacks()));
        }
        if (found.isEmpty()) {
            return "No player-placed storage within " + range + " blocks of the player. Chests, barrels, shulker"
                    + " boxes, minecarts and boats placed before MineAi started tracking placements are not known.";
        }
        found.sort(Comparator.comparingDouble(Found::distance));
        return "Storage within " + range + " blocks of the player (who is at x=" + origin.getX() + ", y="
                + origin.getY() + ", z=" + origin.getZ() + "), nearest first:\n"
                + found.stream().map(Found::line).collect(Collectors.joining("\n"));
    }

    @Tool("Finds a specific item in nearby tracked player-placed storage and owned chested animals."
            + " Returns matching quantities and positions, nearest first, and the total available."
            + " To highlight a matching storage position with particles, call highlightStorage separately."
            + " Use to locate items or find missing crafting materials. Does not include the player's inventory,"
            + " move items, or craft anything. Only searches loaded storage in the player's dimension.")
    public String findItemInStorage(
            @P("Exact item ID, such as minecraft:diamond or minecraft:oak_planks") String itemId,
            @P(value = "Quantity needed across all matching storage", defaultValue = "1") int count,
            @P(value = "Search radius in blocks, up to 64", defaultValue = "32") int radius) {
        ResourceLocation id = itemId == null ? null : ResourceLocation.tryParse(itemId.trim());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return "Unknown item ID: " + itemId + ". Use a registered item ID such as minecraft:diamond.";
        }
        if (count < 1) {
            return "The quantity needed must be at least 1.";
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ServerPlayer player = context.player();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int range = Math.clamp(radius, 1, MAX_RADIUS);
        List<Found> found = new ArrayList<>();
        long total = 0;
        for (Storage storage : collectStorage(level, origin, range)) {
            List<ItemStack> matches = storage.stacks().stream()
                    .filter(stack -> !stack.isEmpty() && stack.is(item))
                    .toList();
            if (matches.isEmpty()) {
                continue;
            }
            total += matches.stream().mapToLong(ItemStack::getCount).sum();
            found.add(entry(storage.kind(), storage.name(), storage.pos(), origin, matches));
        }
        if (found.isEmpty()) {
            return "No " + id + " found in known storage within " + range
                    + " blocks. Storage placed before placement tracking began may not be known.";
        }
        found.sort(Comparator.comparingDouble(Found::distance));
        return "Found " + total + " of " + id + " in storage within " + range + " blocks; needed " + count
                + ". " + (total >= count ? "Enough across these containers." : "Missing " + (count - total) + ".")
                + " Matching storage, nearest first:\n"
                + found.stream().map(Found::line).collect(Collectors.joining("\n"));
    }

    @Tool("Highlights a storage position for about 10 seconds with particles visible only to the requesting player."
            + " Use a position returned by listNearbyContainers or findItemInStorage in that player's dimension." + 
            "When user asks to show, help find item and similar, you suppose to highlight the chests which matches user's query. without asking for permission to highlight")
    public String highlightStorage(
            @P("Storage block position as an object with x, y and z coordinates") StoragePosition storage) {
        ServerPlayer target = context.player();
        if (storage == null) {
            return "A storage position is required.";
        }
        BlockPos pos = new BlockPos(storage.x(), storage.y(), storage.z());
        if (!target.serverLevel().isLoaded(pos)) {
            return "The storage position is not loaded in the player's dimension.";
        }
        StorageHighlights.show(target, pos);
        return "Highlighting storage at x=" + storage.x() + ", y=" + storage.y() + ", z=" + storage.z()
                + " for " + target.getGameProfile().getName() + " for about 10 seconds.";
    }

    private static List<Storage> collectStorage(ServerLevel level, BlockPos origin, int range) {
        List<Storage> storage = new ArrayList<>();
        findBlocks(level, origin, range, storage);
        findEntities(level, origin, range, storage);
        return storage;
    }

    static boolean isStorage(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }

    // Chested animals aren't here: they need no placement tracking (see PlacedContainers).
    static boolean isStorage(Entity entity) {
        return entity instanceof MinecartChest || entity instanceof ChestBoat;
    }

    private static void findBlocks(ServerLevel level, BlockPos origin, int range, List<Storage> found) {
        List<RandomizableContainerBlockEntity> containers = nearbyBlockContainers(level, origin, range);
        // Process nearer halves first so each double chest is listed at its nearest position.
        containers.sort(Comparator.comparingDouble(container -> container.getBlockPos().distSqr(origin)));

        Set<BlockPos> seen = new HashSet<>();
        for (RandomizableContainerBlockEntity container : containers) {
            if (!seen.add(container.getBlockPos())) {
                continue;
            }
            // A double chest is two block entities; list it once, from its nearer half.
            List<RandomizableContainerBlockEntity> halves = new ArrayList<>(List.of(container));
            otherHalf(level, container).filter(other -> seen.add(other.getBlockPos())).ifPresent(halves::add);
            // Reading an unopened loot chest would generate its items. The other half may be generated.
            if (halves.stream().anyMatch(half -> half.getLootTable() != null)) {
                continue;
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (RandomizableContainerBlockEntity half : halves) {
                for (int slot = 0; slot < half.getContainerSize(); slot++) {
                    stacks.add(half.getItem(slot));
                }
            }
            String kind = (halves.size() == 2 ? "Double " : "") + container.getBlockState().getBlock().getName().getString();
            Component name = halves.stream().filter(Nameable::hasCustomName).findFirst()
                    .map(Nameable::getCustomName).orElse(null);
            found.add(new Storage(kind, name, container.getBlockPos(), stacks));
        }
    }

    private static List<RandomizableContainerBlockEntity> nearbyBlockContainers(ServerLevel level,
            BlockPos origin, int range) {
        List<RandomizableContainerBlockEntity> containers = new ArrayList<>();
        int minChunkX = SectionPos.blockToSectionCoord(origin.getX() - range);
        int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + range);
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ() - range);
        int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + range);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // getChunkNow never loads or generates a chunk just to look inside it.
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof RandomizableContainerBlockEntity container && isStorage(container)
                            && PlacedContainers.placedByPlayer(container)
                            && container.getBlockPos().closerThan(origin, range)) {
                        containers.add(container);
                    }
                }
            }
        }
        return containers;
    }

    private static void findEntities(ServerLevel level, BlockPos origin, int range, List<Storage> found) {
        // Like the chunk scan, this only sees entities that are already loaded.
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(range),
                entity -> entity.blockPosition().closerThan(origin, range))) {
            List<ItemStack> stacks;
            if (isStorage(entity) && entity instanceof ContainerEntity container
                    && PlacedContainers.placedByPlayer(entity) && container.getLootTable() == null) {
                stacks = container.getItemStacks();
            } else if (entity instanceof AbstractChestedHorse animal && animal.hasChest()
                    && animal.getOwnerUUID() != null) {
                // Slots 500 and up are the horse.0, horse.1, ... slots of /item: the chest, after the saddle.
                stacks = new ArrayList<>();
                for (int slot = 0; slot < animal.getInventoryColumns() * 3; slot++) {
                    stacks.add(animal.getSlot(500 + slot).get());
                }
            } else {
                continue;
            }
            found.add(new Storage(entity.getType().getDescription().getString(), entity.getCustomName(),
                    entity.blockPosition(), stacks));
        }
    }

    // The other half of a double chest, if the chunk it is in is loaded.
    private static Optional<RandomizableContainerBlockEntity> otherHalf(ServerLevel level,
            RandomizableContainerBlockEntity container) {
        BlockState state = container.getBlockState();
        if (!(container instanceof ChestBlockEntity) || !state.hasProperty(ChestBlock.TYPE)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        BlockPos otherPos = container.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
        return level.isLoaded(otherPos) && level.getBlockEntity(otherPos) instanceof ChestBlockEntity other
                ? Optional.of(other)
                : Optional.empty();
    }

    private static Found entry(String kind, @Nullable Component customName, BlockPos pos, BlockPos origin,
            Iterable<ItemStack> stacks) {
        String name = customName == null ? "" : " named \"" + customName.getString() + "\"";
        double distance = Math.sqrt(pos.distSqr(origin));
        return new Found(distance, "- " + kind + name + " at x=" + pos.getX() + ", y=" + pos.getY() + ", z="
                + pos.getZ() + " (" + Math.round(distance) + " blocks away): " + summarizeItems(stacks));
    }

    private static String summarizeItems(Iterable<ItemStack> stacks) {
        // Same-named stacks add up, so 3 stacks of cobblestone read as 192 Cobblestone.
        Map<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                items.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }
        return items.isEmpty() ? "empty" : items.entrySet().stream()
                .map(item -> item.getValue() + " " + item.getKey())
                .collect(Collectors.joining(", "));
    }
}
