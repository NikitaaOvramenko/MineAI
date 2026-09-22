package io.github.nikitaaovramenko.mineai.blueprint;

// How a build treats what is already in its box.
public enum BuildMode {
    // Only fills empty space (air, water, grass): nothing the player built or mined is lost.
    ADD,
    // Makes the box match the blueprint, except for containers and other block entities, and unbreakable blocks.
    REPLACE
}
