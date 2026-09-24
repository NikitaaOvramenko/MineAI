package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.List;

import dev.langchain4j.model.output.structured.Description;

// What the model sends to BuildTools.planBuild. The descriptions are the model's only documentation.
@Description("A building in the player's frame of reference. x runs from the left edge (0) to the right, y from"
        + " the player's feet level (0, the first layer above the ground) up, z from the side nearest the player"
        + " (0) away from them. Every position must lie inside width x height x depth.")
public record Blueprint(
        @Description("Size along x, left to right, in blocks") int width,
        @Description("Size along y, bottom to top, in blocks") int height,
        @Description("Size along z, near to far, in blocks") int depth,
        @Description("What to build, applied in order; where operations overlap, the later one wins, so walls"
                + " come before the windows and doors cut into them") List<BuildOperation> operations) {}
