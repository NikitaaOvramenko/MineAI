package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Clear;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Fill;

import static org.junit.Assert.*;

public class BuildDiffTest {
    // Block states are plain names here.
    private static final BuildDiff.CellRules<String> RULES = new BuildDiff.CellRules<>() {
        @Override
        public String air() {
            return "air";
        }

        @Override
        public boolean canBeReplaced(String state) {
            return Set.of("air", "water", "grass").contains(state);
        }

        @Override
        public boolean canClear(String state) {
            return Set.of("air", "grass").contains(state);
        }

        @Override
        public boolean mayRemove(String state) {
            return !Set.of("chest", "bedrock").contains(state);
        }
    };

    // A row of three cells along x, holding the given blocks.
    private static BuildDiff.Result<String> diff(BuildOperation operation, BuildMode mode, String... row) {
        TargetGrid grid = BlueprintCompiler.compile(new Blueprint(3, 1, 1, List.of(operation)), 32768);
        return BuildDiff.diff(grid, mode, (block, pos) -> block.block(), pos -> row[pos.x()], RULES);
    }

    private static Fill stoneRow() {
        return new Fill(new LocalPos(0, 0, 0), new LocalPos(2, 0, 0), "stone");
    }

    private static Clear clearRow() {
        return new Clear(new LocalPos(0, 0, 0), new LocalPos(2, 0, 0));
    }

    private static Map<Integer, String> changes(BuildDiff.Result<String> result) {
        return result.changes().stream().collect(java.util.stream.Collectors.toMap(
                change -> change.pos().x(), change -> change.previous() + "->" + change.desired()));
    }

    @Test
    public void addModeOnlyBuildsInEmptySpace() {
        var result = diff(stoneRow(), BuildMode.ADD, "air", "grass", "dirt");
        assertEquals(Map.of(0, "air->stone", 1, "grass->stone"), changes(result));
        assertEquals(2, result.placed());
        assertEquals(1, result.kept());
    }

    @Test
    public void addModeOnlyClearsLooseBlocks() {
        var result = diff(clearRow(), BuildMode.ADD, "grass", "water", "dirt");
        assertEquals(Map.of(0, "grass->air"), changes(result));
        assertEquals(1, result.removed());
        assertEquals(2, result.kept());
    }

    @Test
    public void replaceModeMatchesTheBlueprintButSparesBlockEntities() {
        var result = diff(stoneRow(), BuildMode.REPLACE, "air", "dirt", "chest");
        assertEquals(Map.of(0, "air->stone", 1, "dirt->stone"), changes(result));
        assertEquals(1, result.placed());
        assertEquals(1, result.replaced());
        assertEquals(1, result.protectedCells());

        var cleared = diff(clearRow(), BuildMode.REPLACE, "water", "dirt", "bedrock");
        assertEquals(Map.of(0, "water->air", 1, "dirt->air"), changes(cleared));
        assertEquals(2, cleared.removed());
        assertEquals(1, cleared.protectedCells());
    }

    @Test
    public void cellsThatAlreadyMatchAreLeftOut() {
        var result = diff(stoneRow(), BuildMode.REPLACE, "stone", "stone", "stone");
        assertTrue(result.changes().isEmpty());
        assertEquals(0, result.placed() + result.replaced() + result.kept());
    }
}
