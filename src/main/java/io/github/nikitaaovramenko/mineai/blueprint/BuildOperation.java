package io.github.nikitaaovramenko.mineai.blueprint;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.langchain4j.model.output.structured.Description;

// The steps of a blueprint. LangChain4j shows the model each record as one choice, told apart by a "type"
// property holding the record's name, so renaming a record changes what the model has to send.
// Optional fields are nullable and marked required = false; tool parameters make fields required otherwise.
public sealed interface BuildOperation {
    String BLOCK = "A block id such as minecraft:oak_planks. Properties may follow in brackets, like"
            + " minecraft:oak_log[axis=x]; directions there are the player's: north is away from the player,"
            + " south toward them, east to their right, west to their left.";

    @Description("Fills a box with one block: a floor, a ceiling, a pillar, a window or a single block")
    record Fill(
            @Description("One corner of the box") LocalPos from,
            @Description("The opposite corner") LocalPos to,
            @Description(BLOCK) String block) implements BuildOperation {}

    @Description("The four side walls of a box, without its top and bottom")
    record Walls(
            @Description("One corner of the box") LocalPos from,
            @Description("The opposite corner") LocalPos to,
            @Description(BLOCK) String block) implements BuildOperation {}

    @Description("Makes a box empty, such as the room inside a building. In ADD mode only grass and snow are"
            + " cleared there; REPLACE mode clears everything")
    record Clear(
            @Description("One corner of the box") LocalPos from,
            @Description("The opposite corner") LocalPos to) implements BuildOperation {}

    @Description("A door, two blocks tall")
    record Door(
            @Description("Where its lower half goes, usually in a wall at floor level") LocalPos at,
            @Description("The side of the building the door is in, FRONT being the side nearest the player; the"
                    + " door faces into the building") Side side,
            @Description("A door block such as minecraft:oak_door") String block) implements BuildOperation {}

    @Description("A gable roof of stairs rising from two opposite sides of the walls it covers to a ridge in the"
            + " middle; the triangles under both ends of the ridge can be filled with the gable block")
    record GableRoof(
            @Description("One corner of the walls the roof covers, at the height just above their top")
            LocalPos from,
            @Description("The opposite corner, at the same y") LocalPos to,
            @Description("A stairs block such as minecraft:spruce_stairs") String stairs,
            @JsonProperty(required = false)
            @Description("How many blocks the roof reaches past the walls on every side, continuing its slope"
                    + " down; 0 to 3, default 0. 1 looks best but needs that much room in the blueprint around"
                    + " and below the roof's edge")
            Integer overhang,
            @JsonProperty(required = false)
            @Description("WIDTH: the ridge runs left to right. DEPTH: it runs from the near side to the far side."
                    + " Default: along the longer side") RidgeAlong ridgeAlong,
            @JsonProperty(required = false)
            @Description("The block for the top row, needed when the roof spans an odd number of blocks, such as"
                    + " minecraft:spruce_planks") String ridge,
            @JsonProperty(required = false)
            @Description("The block that fills the triangular walls under both ends of the ridge, such as"
                    + " minecraft:spruce_planks; default none") String gable) implements BuildOperation {}

    @Description("A side of the building, as the player sees it")
    enum Side { FRONT, BACK, LEFT, RIGHT }

    @Description("WIDTH: the ridge runs left to right. DEPTH: it runs from the near side to the far side")
    enum RidgeAlong { WIDTH, DEPTH }
}
