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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
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

    @Tool("Lists the storage players placed near the player and everything in it: chests, barrels and shulker"
            + " boxes, minecarts and boats with chests, and donkeys, mules and llamas carrying chests. For each, its"
            + " kind, name and position, and each item's name and count. Storage the world generated is left out."
            + " Use it when the player asks what they have stored, how many of an item they own, or where"
            + " something is.")
    public String listNearbyContainers(@P(value = "How far from the player to look, in blocks, up to 64",
            defaultValue = "32") int radius) {
        ServerPlayer player = context.player();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int range = Math.clamp(radius, 1, MAX_RADIUS);

        List<Found> found = new ArrayList<>();
        findBlocks(level, origin, range, found);
        findEntities(level, origin, range, found);
        if (found.isEmpty()) {
            return "No player-placed storage within " + range + " blocks of the player. Chests, barrels, shulker"
                    + " boxes, minecarts and boats placed before MineAi started tracking placements are not known.";
        }
        found.sort(Comparator.comparingDouble(Found::distance));
        return "Storage within " + range + " blocks of the player (who is at x=" + origin.getX() + ", y="
                + origin.getY() + ", z=" + origin.getZ() + "), nearest first:\n"
                + found.stream().map(Found::line).collect(Collectors.joining("\n"));
    }

    static boolean isStorage(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }

    // Chested animals aren't here: they need no placement tracking (see PlacedContainers).
    static boolean isStorage(Entity entity) {
        return entity instanceof MinecartChest || entity instanceof ChestBoat;
    }

    private static void findBlocks(ServerLevel level, BlockPos origin, int range, List<Found> found) {
        List<RandomizableContainerBlockEntity> containers = new ArrayList<>();
        for (int chunkX = SectionPos.blockToSectionCoord(origin.getX() - range);
                chunkX <= SectionPos.blockToSectionCoord(origin.getX() + range); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(origin.getZ() - range);
                    chunkZ <= SectionPos.blockToSectionCoord(origin.getZ() + range); chunkZ++) {
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
        containers.sort(Comparator.comparingDouble(container -> container.getBlockPos().distSqr(origin)));

        Set<BlockPos> seen = new HashSet<>();
        for (RandomizableContainerBlockEntity container : containers) {
            if (!seen.add(container.getBlockPos())) {
                continue;
            }
            // A double chest is two block entities; list it once, from its nearer half.
            List<RandomizableContainerBlockEntity> halves = new ArrayList<>(List.of(container));
            otherHalf(level, container).filter(other -> seen.add(other.getBlockPos())).ifPresent(halves::add);
            // An unopened loot chest gets its items when first opened, so reading it would generate them now.
            // A player-placed chest has no loot table, but a double chest's other half can be a generated one.
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
            found.add(entry(kind, name, container.getBlockPos(), origin, stacks));
        }
    }

    private static void findEntities(ServerLevel level, BlockPos origin, int range, List<Found> found) {
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
            found.add(entry(entity.getType().getDescription().getString(), entity.getCustomName(),
                    entity.blockPosition(), origin, stacks));
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
        // Same-named stacks add up, so 3 stacks of cobblestone read as 192 Cobblestone.
        Map<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                items.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }
        String contents = items.isEmpty() ? "empty" : items.entrySet().stream()
                .map(item -> item.getValue() + " " + item.getKey())
                .collect(Collectors.joining(", "));
        String name = customName == null ? "" : " named \"" + customName.getString() + "\"";
        double distance = Math.sqrt(pos.distSqr(origin));
        return new Found(distance, "- " + kind + name + " at x=" + pos.getX() + ", y=" + pos.getY() + ", z="
                + pos.getZ() + " (" + Math.round(distance) + " blocks away): " + contents);
    }
}
