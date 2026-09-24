package io.github.nikitaaovramenko.mineai.building;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.nikitaaovramenko.mineai.MineAi;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

// Each player's last few finished builds, newest first, for /mineai undo. In memory only, so a server restart
// forgets them. Only touched on the server thread.
@EventBusSubscriber(modid = MineAi.MODID)
public final class BuildHistory {
    private static final int KEPT_PER_PLAYER = 5;
    private static final Map<UUID, Deque<Entry>> HISTORY = new HashMap<>();

    // placed is the block the build left there, after its neighbours reshaped it. Undo only restores previous
    // where that block is still there, so it never undoes what someone did after the build.
    public record Change(BlockPos pos, BlockState previous, BlockState placed) {}

    public record Entry(ResourceKey<Level> dimension, BoundingBox bounds, List<Change> changes) {}

    private BuildHistory() {}

    static void push(UUID playerId, Entry entry) {
        Deque<Entry> entries = HISTORY.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        entries.push(entry);
        while (entries.size() > KEPT_PER_PLAYER) {
            entries.removeLast();
        }
    }

    static Optional<Entry> pop(UUID playerId) {
        Deque<Entry> entries = HISTORY.get(playerId);
        return entries == null ? Optional.empty() : Optional.ofNullable(entries.poll());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        HISTORY.clear();
    }
}
