package io.github.nikitaaovramenko.mineai.building;

import java.util.UUID;

import io.github.nikitaaovramenko.mineai.blueprint.Blueprint;
import io.github.nikitaaovramenko.mineai.blueprint.BuildDiff;
import io.github.nikitaaovramenko.mineai.blueprint.BuildMode;
import io.github.nikitaaovramenko.mineai.tools.RequestOrigin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// A planned building waiting for the player's confirmation. The diff was taken against the world as it was
// then; building skips any block that has changed since, so the player gets what they were shown or less.
public record BuildPlan(UUID playerId, RequestOrigin origin, Blueprint blueprint, BuildMode mode, int distance,
        int raise, BuildFrame frame, BoundingBox bounds, BuildDiff.Result<BlockState> diff, int createdTick) {}
