package io.github.nikitaaovramenko.mineai.building;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.joml.Vector3f;

import io.github.nikitaaovramenko.mineai.MineAi;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The plans waiting for their player's confirmation, one per player, each outlined in particles only its
// player sees. Plans live in memory: they go when confirmed, cancelled or replaced, when the player leaves,
// after ten minutes, and when the server stops. Only touched on the server thread.
@EventBusSubscriber(modid = MineAi.MODID)
public final class PendingBuilds {
    private static final int EXPIRY_TICKS = 20 * 60 * 10;
    private static final int OUTLINE_INTERVAL_TICKS = 10;
    private static final DustParticleOptions OUTLINE = new DustParticleOptions(new Vector3f(0.3f, 0.9f, 0.4f), 1.0f);
    private static final Map<UUID, BuildPlan> PLANS = new HashMap<>();

    private PendingBuilds() {}

    public static Optional<BuildPlan> get(UUID playerId) {
        return Optional.ofNullable(PLANS.get(playerId));
    }

    public static void put(BuildPlan plan) {
        PLANS.put(plan.playerId(), plan);
    }

    public static Optional<BuildPlan> take(UUID playerId) {
        return Optional.ofNullable(PLANS.remove(playerId));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        for (Iterator<BuildPlan> plans = PLANS.values().iterator(); plans.hasNext(); ) {
            BuildPlan plan = plans.next();
            ServerPlayer player = server.getPlayerList().getPlayer(plan.playerId());
            if (tick - plan.createdTick() > EXPIRY_TICKS) {
                plans.remove();
                if (player != null) {
                    player.sendSystemMessage(BuildMessages.line("Your build plan expired. Ask again to make a new one."));
                }
            } else if (player != null && tick % OUTLINE_INTERVAL_TICKS == 0
                    && player.serverLevel().dimension() == plan.frame().dimension()) {
                outline(player, plan.bounds());
            }
        }
    }

    @SubscribeEvent
    static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PLANS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        PLANS.clear();
    }

    // Particles along the twelve edges of the building's box, one per block.
    private static void outline(ServerPlayer player, BoundingBox box) {
        int x0 = box.minX();
        int y0 = box.minY();
        int z0 = box.minZ();
        int x1 = box.maxX() + 1;
        int y1 = box.maxY() + 1;
        int z1 = box.maxZ() + 1;
        for (int x = x0; x <= x1; x++) {
            dot(player, x, y0, z0);
            dot(player, x, y1, z0);
            dot(player, x, y0, z1);
            dot(player, x, y1, z1);
        }
        for (int y = y0 + 1; y < y1; y++) {
            dot(player, x0, y, z0);
            dot(player, x1, y, z0);
            dot(player, x0, y, z1);
            dot(player, x1, y, z1);
        }
        for (int z = z0 + 1; z < z1; z++) {
            dot(player, x0, y0, z);
            dot(player, x1, y0, z);
            dot(player, x0, y1, z);
            dot(player, x1, y1, z);
        }
    }

    private static void dot(ServerPlayer player, double x, double y, double z) {
        player.serverLevel().sendParticles(player, OUTLINE, true, x, y, z, 1, 0, 0, 0, 0);
    }
}
