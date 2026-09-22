package io.github.nikitaaovramenko.mineai.building;

import java.util.Map;
import java.util.UUID;

import io.github.nikitaaovramenko.mineai.blueprint.Blueprint;
import io.github.nikitaaovramenko.mineai.blueprint.BlueprintCompiler;
import io.github.nikitaaovramenko.mineai.blueprint.BuildDiff;
import io.github.nikitaaovramenko.mineai.blueprint.BuildMode;
import io.github.nikitaaovramenko.mineai.blueprint.PlannedBlock;
import io.github.nikitaaovramenko.mineai.blueprint.TargetGrid;
import io.github.nikitaaovramenko.mineai.tools.RequestOrigin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// Turns a blueprint into a BuildPlan: compiles it, places it in front of the player, resolves its blocks and
// compares it with the world. Reads the world, never changes it; that is BuildJobs' job, after confirmation.
public final class BuildPlanner {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static final BuildDiff.CellRules<BlockState> RULES = new BuildDiff.CellRules<>() {
        @Override
        public BlockState air() {
            return AIR;
        }

        @Override
        public boolean canBeReplaced(BlockState state) {
            return state.canBeReplaced();
        }

        @Override
        public boolean canClear(BlockState state) {
            return state.canBeReplaced() && state.getFluidState().isEmpty();
        }

        // Never containers and other block entities: replacing one would lose what it holds. Never half of a
        // door or bed either, as the other half would break and drop.
        @Override
        public boolean mayRemove(BlockState state) {
            return !state.hasBlockEntity()
                    && state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 0
                    && !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && !state.hasProperty(BlockStateProperties.BED_PART);
        }
    };

    private BuildPlanner() {}

    public static BuildPlan plan(ServerLevel level, UUID playerId, RequestOrigin origin, Blueprint blueprint,
            BuildMode mode, int distance, int raise) {
        int limit = level.getGameRules().getInt(GameRules.RULE_COMMAND_MODIFICATION_BLOCK_LIMIT);
        TargetGrid grid = BlueprintCompiler.compile(blueprint, limit);
        BuildFrame frame = BuildFrame.inFrontOf(origin, grid.width(), distance, raise);
        BoundingBox bounds = frame.bounds(grid.width(), grid.height(), grid.depth());
        requireInWorld(level, bounds);
        Map<PlannedBlock, BlockState> states = BlockResolver.resolveAll(grid.distinctBlocks(),
                level.holderLookup(Registries.BLOCK));
        Rotation rotation = frame.rotation();
        // Turned where it will stand: a modded block may rotate depending on its position.
        BuildDiff.Result<BlockState> diff = BuildDiff.diff(grid, mode,
                (block, pos) -> states.get(block).rotate(level, frame.toWorld(pos), rotation),
                pos -> level.getBlockState(frame.toWorld(pos)), RULES);
        return new BuildPlan(playerId, origin, blueprint, mode, distance, raise, frame, bounds, diff,
                level.getServer().getTickCount());
    }

    // Must pass before any block in bounds is read: reading one in a chunk that isn't loaded would load the
    // chunk, or even generate it, which a tool must never cause.
    static void requireInWorld(ServerLevel level, BoundingBox bounds) {
        if (level.isOutsideBuildHeight(bounds.minY()) || level.isOutsideBuildHeight(bounds.maxY())) {
            throw new IllegalArgumentException("The building would reach from y=" + bounds.minY() + " to y="
                    + bounds.maxY() + ", but this world only builds from y=" + level.getMinBuildHeight() + " to y="
                    + (level.getMaxBuildHeight() - 1) + ". Make it lower or change raise.");
        }
        for (int chunkX = SectionPos.blockToSectionCoord(bounds.minX());
                chunkX <= SectionPos.blockToSectionCoord(bounds.maxX()); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(bounds.minZ());
                    chunkZ <= SectionPos.blockToSectionCoord(bounds.maxZ()); chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    throw new IllegalArgumentException("Part of the building area is too far from the player to be"
                            + " loaded. Make the building smaller or bring it closer.");
                }
            }
        }
    }
}
