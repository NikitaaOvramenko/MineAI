package io.github.nikitaaovramenko.mineai.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.nikitaaovramenko.mineai.MineAi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = MineAi.MODID)
public final class StorageHighlights {
    private static final int DURATION_TICKS = 200; // About 10 seconds
    private static final int INTERVAL_TICKS = 10; // About 0.5 seconds

    private record Marker(UUID playerId, ResourceKey<Level> dimension, BlockPos pos) {}

    private static final Map<Marker, Integer> active = new HashMap<>();

    private StorageHighlights() {}

    // Call on the server thread. Highlighting the same position again resets its timer.
    // Markers stay where storage was found, including storage carried by moving entities.
    public static void show(ServerPlayer player, BlockPos pos) {
        Marker marker = new Marker(player.getUUID(), player.serverLevel().dimension(), pos.immutable());
        active.put(marker, DURATION_TICKS);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Marker marker = entry.getKey();
            int remaining = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(marker.playerId());

            if (remaining <= 0 || player == null
                    || !player.serverLevel().dimension().equals(marker.dimension())) {
                iterator.remove();
                continue;
            }

            if (remaining % INTERVAL_TICKS == 0 && player.serverLevel().isLoaded(marker.pos())) {
                BlockPos pos = marker.pos();
                player.serverLevel().sendParticles(player, ParticleTypes.ENCHANT, true,
                        pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                        10, 0.3, 0.2, 0.3, 0.0);
            }
            entry.setValue(remaining - 1);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active.clear();
    }
}
