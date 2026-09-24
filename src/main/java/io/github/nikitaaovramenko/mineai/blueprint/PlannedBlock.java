package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.Map;

// A block as the blueprint asks for it: the model's block string, plus properties an operation sets itself,
// such as a stair's facing. Directions are in the blueprint's frame, where north is away from the player;
// the game side turns them to the world. Records compare by value, so equal blocks resolve once.
public record PlannedBlock(String block, Map<String, String> properties, Kind kind) {
    // What the operation needs the block to be, checked once the id is known to the game.
    public enum Kind { ANY, DOOR, STAIRS }

    public PlannedBlock {
        properties = Map.copyOf(properties);
    }

    public static PlannedBlock of(String block) {
        return new PlannedBlock(block, Map.of(), Kind.ANY);
    }
}
