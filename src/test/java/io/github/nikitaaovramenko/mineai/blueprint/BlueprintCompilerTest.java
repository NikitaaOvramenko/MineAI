package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Clear;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Door;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Fill;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.GableRoof;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.RidgeAlong;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Side;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Walls;

import static org.junit.Assert.*;

public class BlueprintCompilerTest {
    private static final String STAIRS = "minecraft:oak_stairs";
    private static final String EAST = STAIRS + "[facing=east,half=bottom]";
    private static final String WEST = STAIRS + "[facing=west,half=bottom]";

    private static LocalPos at(int x, int y, int z) {
        return new LocalPos(x, y, z);
    }

    private static TargetGrid compile(int width, int height, int depth, BuildOperation... operations) {
        return BlueprintCompiler.compile(new Blueprint(width, height, depth, Arrays.asList(operations)), 32768);
    }

    private static String problems(int width, int height, int depth, BuildOperation... operations) {
        return assertThrows(IllegalArgumentException.class,
                () -> compile(width, height, depth, operations)).getMessage();
    }

    // "air" for a Remove cell, null where nothing is planned, else the block with its properties in brackets.
    private static String cell(TargetGrid grid, int x, int y, int z) {
        PlannedCell cell = grid.get(x, y, z);
        if (cell == null) {
            return null;
        }
        if (cell instanceof PlannedCell.Remove) {
            return "air";
        }
        PlannedBlock block = ((PlannedCell.Place) cell).block();
        return block.properties().isEmpty() ? block.block() : block.block() + block.properties().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(property -> property.getKey() + "=" + property.getValue())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static GableRoof roof(LocalPos from, LocalPos to, Integer overhang, RidgeAlong ridgeAlong, String ridge,
            String gable) {
        return new GableRoof(from, to, STAIRS, overhang, ridgeAlong, ridge, gable);
    }

    @Test
    public void fillsABoxGivenAnyTwoOppositeCorners() {
        TargetGrid grid = compile(3, 2, 2, new Fill(at(2, 1, 0), at(0, 0, 1), "minecraft:stone"));
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    assertEquals("minecraft:stone", cell(grid, x, y, z));
                }
            }
        }
    }

    @Test
    public void wallsLeaveTheInsideAlone() {
        TargetGrid grid = compile(3, 2, 3, new Walls(at(0, 0, 0), at(2, 1, 2), "minecraft:oak_planks"));
        assertEquals("minecraft:oak_planks", cell(grid, 0, 1, 1));
        assertEquals("minecraft:oak_planks", cell(grid, 1, 0, 2));
        assertNull(cell(grid, 1, 0, 1));
        assertNull(cell(grid, 1, 1, 1));
    }

    @Test
    public void laterOperationsWinWhereTheyOverlap() {
        TargetGrid grid = compile(3, 3, 3,
                new Fill(at(0, 0, 0), at(2, 2, 2), "minecraft:stone"),
                new Clear(at(1, 1, 1), at(1, 1, 1)),
                new Fill(at(1, 1, 0), at(1, 1, 0), "minecraft:glass"));
        assertEquals("minecraft:stone", cell(grid, 0, 0, 0));
        assertEquals("air", cell(grid, 1, 1, 1));
        assertEquals("minecraft:glass", cell(grid, 1, 1, 0));
    }

    @Test
    public void doorsHaveTwoHalvesFacingIntoTheBuilding() {
        TargetGrid grid = compile(3, 3, 3,
                new Door(at(1, 0, 0), Side.FRONT, "minecraft:oak_door"),
                new Door(at(0, 0, 1), Side.LEFT, "minecraft:oak_door"));
        assertEquals("minecraft:oak_door[facing=north,half=lower]", cell(grid, 1, 0, 0));
        assertEquals("minecraft:oak_door[facing=north,half=upper]", cell(grid, 1, 1, 0));
        assertEquals("minecraft:oak_door[facing=east,half=lower]", cell(grid, 0, 0, 1));
    }

    @Test
    public void aDoorNeedsRoomForItsUpperHalf() {
        assertTrue(problems(3, 2, 3, new Door(at(1, 1, 0), Side.FRONT, "minecraft:oak_door")).contains("upper half"));
    }

    @Test
    public void aRoofOverAnEvenSpanClimbsToTwoRowsOfStairs() {
        TargetGrid grid = compile(4, 2, 2, roof(at(0, 0, 0), at(3, 0, 1), null, RidgeAlong.DEPTH, null, null));
        for (int z = 0; z < 2; z++) {
            assertEquals(EAST, cell(grid, 0, 0, z));
            assertEquals(WEST, cell(grid, 3, 0, z));
            assertEquals(EAST, cell(grid, 1, 1, z));
            assertEquals(WEST, cell(grid, 2, 1, z));
            assertNull(cell(grid, 1, 0, z));
        }
    }

    @Test
    public void aRoofOverAnOddSpanNeedsARidgeBlock() {
        assertTrue(problems(5, 3, 2, roof(at(0, 0, 0), at(4, 0, 1), null, RidgeAlong.DEPTH, null, null))
                .contains("ridge"));
        TargetGrid grid = compile(5, 3, 2,
                roof(at(0, 0, 0), at(4, 0, 1), null, RidgeAlong.DEPTH, "minecraft:oak_planks", null));
        assertEquals("minecraft:oak_planks", cell(grid, 2, 2, 0));
        assertEquals(EAST, cell(grid, 1, 1, 0));
    }

    @Test
    public void theOverhangContinuesTheSlopeDownAndTheGablesFillTheEndWalls() {
        // Walls at x 1-3 and z 1-3 whose top is at y 0; the roof starts on them at y 1.
        TargetGrid grid = compile(5, 3, 5, roof(at(1, 1, 1), at(3, 1, 3), 1, RidgeAlong.DEPTH,
                "minecraft:oak_planks", "minecraft:spruce_planks"));
        for (int z = 0; z < 5; z++) {
            assertEquals(EAST, cell(grid, 0, 0, z));
            assertEquals(WEST, cell(grid, 4, 0, z));
            assertEquals(EAST, cell(grid, 1, 1, z));
            assertEquals(WEST, cell(grid, 3, 1, z));
            assertEquals("minecraft:oak_planks", cell(grid, 2, 2, z));
        }
        assertEquals("minecraft:spruce_planks", cell(grid, 2, 1, 1));
        assertEquals("minecraft:spruce_planks", cell(grid, 2, 1, 3));
        assertNull("the attic stays open", cell(grid, 2, 1, 2));
        assertNull("no gable below the roof's start", cell(grid, 2, 0, 1));
    }

    @Test
    public void theRidgeRunsAlongTheLongerSideByDefault() {
        TargetGrid deep = compile(2, 1, 5, roof(at(0, 0, 0), at(1, 0, 4), null, null, null, null));
        assertEquals(EAST, cell(deep, 0, 0, 0));
        TargetGrid wide = compile(5, 1, 2, roof(at(0, 0, 0), at(4, 0, 1), null, null, null, null));
        assertEquals(STAIRS + "[facing=north,half=bottom]", cell(wide, 0, 0, 0));
        assertEquals(STAIRS + "[facing=south,half=bottom]", cell(wide, 0, 0, 1));
    }

    @Test
    public void listsEveryProblemAtOnce() {
        String problems = problems(3, 3, 3,
                new Fill(at(0, 0, 0), at(3, 0, 0), "minecraft:stone"),
                new Door(at(1, 0, 0), null, "minecraft:oak_door"),
                new Walls(at(0, 0, 0), at(1, 1, 1), " "));
        assertTrue(problems, problems.contains("Operation 1 (Fill)"));
        assertTrue(problems, problems.contains("Operation 2 (Door) needs side"));
        assertTrue(problems, problems.contains("Operation 3 (Walls) needs block"));
    }

    @Test
    public void reportsEmptyOperations() {
        assertTrue(problems(1, 1, 1, (BuildOperation) null).contains("Operation 1 is empty"));
    }

    @Test
    public void refusesBlueprintsOverTheSizeLimit() {
        var blueprint = new Blueprint(40, 40, 40, List.of(new Fill(at(0, 0, 0), at(0, 0, 0), "minecraft:stone")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BlueprintCompiler.compile(blueprint, 32768))
                .getMessage().contains("32768"));
    }
}
