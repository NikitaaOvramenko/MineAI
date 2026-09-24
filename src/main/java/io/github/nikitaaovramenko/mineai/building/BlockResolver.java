package io.github.nikitaaovramenko.mineai.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.nikitaaovramenko.mineai.blueprint.PlannedBlock;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

// Turns the blueprint's blocks into block states: parses the id and any [properties] the way /setblock does,
// checks the block suits its operation and applies the operation's own properties. Directions stay in the
// blueprint's frame (forward is north); BuildPlanner turns each placed block to the world.
final class BlockResolver {
    private BlockResolver() {}

    // Every bad block is reported at once, like the compiler's problems.
    static Map<PlannedBlock, BlockState> resolveAll(Set<PlannedBlock> blocks, HolderLookup<Block> lookup) {
        Map<PlannedBlock, BlockState> states = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (PlannedBlock block : blocks) {
            try {
                states.put(block, resolve(block, lookup));
            } catch (CommandSyntaxException exception) {
                String problem = block.block() + ": " + exception.getRawMessage().getString();
                if (!problems.contains(problem)) {
                    problems.add(problem);
                }
            } catch (IllegalArgumentException exception) {
                if (!problems.contains(exception.getMessage())) {
                    problems.add(exception.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Some blocks can't be used; fix them (findBlocks looks up real ids)"
                    + " and send the blueprint again:\n- " + String.join("\n- ", problems));
        }
        return states;
    }

    private static BlockState resolve(PlannedBlock planned, HolderLookup<Block> lookup) throws CommandSyntaxException {
        BlockState state = BlockStateParser.parseForBlock(lookup, planned.block(), false).blockState();
        Block block = state.getBlock();
        switch (planned.kind()) {
            case DOOR -> {
                if (!(block instanceof DoorBlock)) {
                    throw new IllegalArgumentException(planned.block() + " is not a door; Door needs one such as"
                            + " minecraft:oak_door.");
                }
            }
            case STAIRS -> {
                if (!(block instanceof StairBlock)) {
                    throw new IllegalArgumentException(planned.block() + " is not stairs; GableRoof needs stairs"
                            + " such as minecraft:oak_stairs.");
                }
            }
            case ANY -> {
                // Two-block things need both halves placed together; only Door knows how.
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || state.hasProperty(BlockStateProperties.BED_PART)) {
                    throw new IllegalArgumentException(planned.block() + " takes two blocks; of those, only"
                            + " doors can be built, with a Door operation.");
                }
            }
        }
        for (Map.Entry<String, String> property : planned.properties().entrySet()) {
            state = with(state, property.getKey(), property.getValue());
        }
        return state;
    }

    // The operations only set properties their kind of block has, so a failure here is a bug of ours.
    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            throw new IllegalStateException(state.getBlock() + " has no property " + name);
        }
        return with(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(
                () -> new IllegalStateException(property.getName() + " can't be " + value)));
    }
}
