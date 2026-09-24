package io.github.nikitaaovramenko.mineai.blueprint;

import dev.langchain4j.model.output.structured.Description;

@Description("A position in the blueprint: x counts from its left edge, y from the ground up, z from its near side")
public record LocalPos(int x, int y, int z) {
    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
