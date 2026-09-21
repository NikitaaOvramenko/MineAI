package io.github.nikitaaovramenko.mineai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

// Tools that change the world for the asking player.
public class PlayerActions {
    private final ToolContext context;

    public PlayerActions(ToolContext context) {
        this.context = context;
    }

    @Tool("Places one block near the player, like /setblock, but only into empty space: air, water, or plants"
            + " such as tall grass. The position is counted from the block the player's feet are in, along the"
            + " direction the player faces.")
    public String placeBlock(
            @P("The block's id, such as minecraft:stone or minecraft:oak_planks") String block,
            @P("How many blocks in front of the player; negative means behind") int forward,
            @P("How many blocks to the player's right; negative means left") int right,
            @P("How many blocks above the player's feet; -1 is the block they stand on") int up) {
        // Placing any block for free is what /setblock does, so only players who may use /setblock.
        if (!context.canUseCommand("setblock")) {
            throw new IllegalStateException("This player is not allowed to place blocks (/setblock is restricted).");
        }
        ServerPlayer player = context.player();
        ServerLevel level = player.serverLevel();

        // The model sends text, the game wants a Block: every block is registered under an id like minecraft:stone.
        ResourceLocation id = ResourceLocation.tryParse(block);
        Block found = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (found == null) {
            throw new IllegalArgumentException("There is no block called " + block + ".");
        }
        BlockState state = found.defaultBlockState();
        String name = found.getName().getString();

        // Forward, right and up turn with the player, so "in front of me" works whichever way they look.
        Direction facing = player.getDirection();
        BlockPos pos = player.blockPosition()
                .relative(facing, forward)
                .relative(facing.getClockWise(), right)
                .above(up);

        // isLoaded is also false above and below the world. It comes first because reading a block in a chunk
        // that isn't loaded would load that chunk, or even generate it.
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("That spot is outside the world or too far away to be loaded.");
        }
        BlockState there = level.getBlockState(pos);
        if (!there.canBeReplaced()) {
            throw new IllegalStateException("There is already " + there.getBlock().getName().getString() + " there.");
        }
        if (!state.canSurvive(level, pos)) {
            throw new IllegalStateException(name + " can't stay there; it may need a block under or behind it.");
        }
        if (!level.isUnobstructed(state, pos, CollisionContext.empty())) {
            throw new IllegalStateException("The player, a mob or a vehicle is in the way.");
        }

        // setBlock alone is silent, like /setblock. Placing by hand also fires NeoForge's place event, which
        // land-claim mods use to say no and PlacedContainers uses to remember chests, so fire it too.
        BlockSnapshot before = BlockSnapshot.create(level.dimension(), level, pos);
        level.setBlockAndUpdate(pos, state);
        if (EventHooks.onBlockPlace(player, before, Direction.UP)) {
            before.restore();
            throw new IllegalStateException("Something stopped the block from being placed there, such as a land claim.");
        }
        return "Placed " + name + " at x=" + pos.getX() + ", y=" + pos.getY() + ", z=" + pos.getZ() + ".";
    }
}