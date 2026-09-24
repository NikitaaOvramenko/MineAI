package io.github.nikitaaovramenko.mineai.building;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.nikitaaovramenko.mineai.MineAi;
import io.github.nikitaaovramenko.mineai.blueprint.BuildDiff;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// Builds confirmed plans and undoes finished builds a few hundred blocks per tick, so a big build never stalls
// the server. One job per player at a time. Jobs live in memory, and a server stop abandons them. Only touched
// on the server thread.
@EventBusSubscriber(modid = MineAi.MODID)
public final class BuildJobs {
    private static final int CHANGES_PER_TICK = 256;
    // No neighbour or shape updates while placing, so nothing half-built cascades, breaks or drops. Each block
    // gets its updates in a final pass once everything is in place, as with /fill and structure placement.
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();

    private record Step(BlockPos pos, BlockState from, BlockState to) {}

    private BuildJobs() {}

    public static boolean isRunning(UUID playerId) {
        return JOBS.containsKey(playerId);
    }

    public static void build(BuildPlan plan, ServerLevel level) {
        List<Step> steps = new ArrayList<>();
        for (BuildDiff.Change<BlockState> change : plan.diff().changes()) {
            steps.add(new Step(plan.frame().toWorld(change.pos()), change.previous(), change.desired()));
        }
        steps.sort(Comparator.comparingInt((Step step) -> phase(level, step))
                .thenComparingInt(step -> step.to().isAir() ? -step.pos().getY() : step.pos().getY()));
        JOBS.put(plan.playerId(), new Job(plan.playerId(), level.dimension(), plan.bounds(), steps, false));
    }

    // Takes changes back newest first, so what was placed on top goes before what holds it up.
    public static void undo(UUID playerId, BuildHistory.Entry entry) {
        List<Step> steps = new ArrayList<>();
        for (BuildHistory.Change change : entry.changes().reversed()) {
            steps.add(new Step(change.pos(), change.placed(), change.previous()));
        }
        JOBS.put(playerId, new Job(playerId, entry.dimension(), entry.bounds(), steps, true));
    }

    // Removals first, top down, so nothing is left hanging; full blocks bottom up, so each has its support;
    // then what attaches to them, such as doors (lower half first), torches and panes; fluids last, once
    // there is something to hold them.
    private static int phase(ServerLevel level, Step step) {
        BlockState state = step.to();
        if (state.isAir()) {
            return 0;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return 3;
        }
        return state.isCollisionShapeFullBlock(level, step.pos()) ? 1 : 2;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int budget = CHANGES_PER_TICK;
        for (Iterator<Job> jobs = JOBS.values().iterator(); jobs.hasNext() && budget > 0; ) {
            Job job = jobs.next();
            ServerLevel level = server.getLevel(job.dimension);
            if (level != null) {
                budget = job.run(server, level, budget);
            }
            if (level == null || job.done()) {
                jobs.remove();
                job.finish(server, level);
            }
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
    }

    private static final class Job {
        private final UUID playerId;
        private final ResourceKey<Level> dimension;
        private final BoundingBox bounds;
        private final List<Step> steps;
        private final boolean undo;
        private final List<Step> applied = new ArrayList<>();
        private int next;
        private int updated;
        // Skipped blocks, by reason.
        private int stale;
        private int obstructed;
        private int refused;
        private int unloaded;

        Job(UUID playerId, ResourceKey<Level> dimension, BoundingBox bounds, List<Step> steps, boolean undo) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.bounds = bounds;
            this.steps = steps;
            this.undo = undo;
        }

        // Returns the budget left for other jobs.
        int run(MinecraftServer server, ServerLevel level, int budget) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            while (budget > 0 && next < steps.size()) {
                apply(level, player, steps.get(next++));
                budget--;
            }
            while (budget > 0 && next == steps.size() && updated < applied.size()) {
                update(level, applied.get(updated++).pos());
                budget--;
            }
            return budget;
        }

        boolean done() {
            return next == steps.size() && updated == applied.size();
        }

        private void apply(ServerLevel level, @Nullable ServerPlayer player, Step step) {
            BlockPos pos = step.pos();
            // Checked first: reading a block in a chunk that isn't loaded would load it.
            if (!level.isLoaded(pos)) {
                unloaded++;
                return;
            }
            BlockState current = level.getBlockState(pos);
            if (current == step.to()) {
                return;
            }
            // Something changed here since the plan was shown (or, for an undo, since the build): leave it.
            if (current != step.from()) {
                stale++;
                return;
            }
            if (!step.to().isAir() && !level.isUnobstructed(step.to(), pos, CollisionContext.empty())) {
                obstructed++;
                return;
            }
            // A build breaks and places blocks for its player, so mods that protect land get their say, as
            // they would if the player did it by hand. An undo only puts back what was there.
            if (!undo && player != null && !current.canBeReplaced()
                    && NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, current, player)).isCanceled()) {
                refused++;
                return;
            }
            BlockSnapshot before = BlockSnapshot.create(level.dimension(), level, pos, FLAGS);
            if (!level.setBlock(pos, step.to(), FLAGS)) {
                stale++;
                return;
            }
            // Also how PlacedContainers learns about chests a build places.
            if (!undo && !step.to().isAir() && EventHooks.onBlockPlace(player, before, Direction.UP)) {
                before.restore();
                refused++;
                return;
            }
            applied.add(step);
        }

        // The updates placing skipped: the block takes its shape from its neighbours (panes connect, a door
        // finds its other half), then its neighbours hear of it (redstone, falling blocks, fences outside).
        private static void update(ServerLevel level, BlockPos pos) {
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            BlockState shaped = Block.updateFromNeighbourShapes(state, level, pos);
            if (shaped != state) {
                level.setBlock(pos, shaped, FLAGS);
            }
            level.blockUpdated(pos, shaped.getBlock());
            shaped.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
        }

        void finish(MinecraftServer server, @Nullable ServerLevel level) {
            if (!undo && level != null && !applied.isEmpty()) {
                List<BuildHistory.Change> changes = new ArrayList<>();
                for (Step step : applied) {
                    BlockState placed = level.isLoaded(step.pos()) ? level.getBlockState(step.pos()) : step.to();
                    changes.add(new BuildHistory.Change(step.pos(), step.from(), placed));
                }
                BuildHistory.push(playerId, new BuildHistory.Entry(dimension, bounds, changes));
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(report());
            }
        }

        private Component report() {
            StringBuilder text = new StringBuilder(undo
                    ? "Undo finished: " + applied.size() + " blocks put back."
                    : "Build finished: " + applied.size() + " blocks changed.");
            skipped(text, stale, undo ? "had changed since the build" : "had changed since the plan was shown");
            skipped(text, obstructed, "had a player, mob or vehicle in the way");
            skipped(text, refused, "were refused by a mod protecting the land");
            skipped(text, unloaded, "were in chunks that weren't loaded");
            if (!undo && !applied.isEmpty()) {
                text.append(" /mineai undo takes it back.");
            }
            return BuildMessages.line(text.toString());
        }

        private static void skipped(StringBuilder text, int count, String reason) {
            if (count > 0) {
                text.append(" Skipped ").append(count).append(" that ").append(reason).append(".");
            }
        }
    }
}
