package io.github.nikitaaovramenko.mineai.building;

import io.github.nikitaaovramenko.mineai.blueprint.LocalPos;
import io.github.nikitaaovramenko.mineai.tools.RequestOrigin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// The blueprint's frame of reference in the world: x to the player's right, y up, z forward, starting at the
// building's near left bottom corner. Fixed when the plan is made, so turning around afterwards changes nothing.
public record BuildFrame(ResourceKey<Level> dimension, BlockPos corner, Direction forward) {
    // distance blocks in front of where the player asked, centered on them, raise blocks up from their feet.
    public static BuildFrame inFrontOf(RequestOrigin origin, int width, int distance, int raise) {
        Direction forward = origin.facing();
        BlockPos corner = origin.position()
                .relative(forward, distance)
                .relative(forward.getClockWise(), -((width - 1) / 2))
                .above(raise);
        return new BuildFrame(origin.dimension(), corner, forward);
    }

    public Direction right() {
        return forward.getClockWise();
    }

    public BlockPos toWorld(LocalPos pos) {
        return corner.relative(right(), pos.x()).above(pos.y()).relative(forward, pos.z());
    }

    // Blueprint directions call forward north (and right east); this turns them to the world.
    public Rotation rotation() {
        return switch (forward) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public BoundingBox bounds(int width, int height, int depth) {
        return BoundingBox.fromCorners(toWorld(new LocalPos(0, 0, 0)),
                toWorld(new LocalPos(width - 1, height - 1, depth - 1)));
    }
}
