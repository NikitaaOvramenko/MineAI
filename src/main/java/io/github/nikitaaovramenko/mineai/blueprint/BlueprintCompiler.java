package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Clear;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Door;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Fill;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.GableRoof;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.RidgeAlong;
import io.github.nikitaaovramenko.mineai.blueprint.BuildOperation.Walls;

// Turns a blueprint into a TargetGrid. Plain geometry without Minecraft, so every shape is unit-tested.
// All problems come back in one message: the model can only fix what it is told about, and every retry
// is another round trip.
public final class BlueprintCompiler {
    public static final int MAX_OPERATIONS = 200;
    private static final int MAX_OVERHANG = 3;
    private static final int MAX_PROBLEMS_SHOWN = 12;

    private interface CellAction {
        void at(int x, int y, int z);
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        void forEach(CellAction action) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        action.at(x, y, z);
                    }
                }
            }
        }
    }

    private BlueprintCompiler() {}

    // maxVolume is the most cells one build may cover; in game, the same limit /fill has.
    public static TargetGrid compile(Blueprint blueprint, int maxVolume) {
        int width = blueprint.width();
        int height = blueprint.height();
        int depth = blueprint.depth();
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("width, height and depth must all be at least 1.");
        }
        long volume = (long) width * height * depth;
        if (volume > maxVolume) {
            throw new IllegalArgumentException("The blueprint is " + width + " x " + height + " x " + depth + " = "
                    + volume + " blocks, more than the " + maxVolume + " one build may cover (the"
                    + " commandModificationBlockLimit game rule, the same limit /fill has).");
        }
        List<BuildOperation> operations = blueprint.operations() == null ? List.of() : blueprint.operations();
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("The blueprint has no operations.");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("The blueprint has " + operations.size() + " operations; the most is "
                    + MAX_OPERATIONS + ". Use bigger boxes.");
        }

        TargetGrid grid = new TargetGrid(width, height, depth);
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            BuildOperation operation = operations.get(i);
            List<String> found = new ArrayList<>();
            switch (operation) {
                case Fill fill -> fill(grid, fill, found);
                case Walls walls -> walls(grid, walls, found);
                case Clear clear -> clear(grid, clear, found);
                case Door door -> door(grid, door, found);
                case GableRoof roof -> roof(grid, roof, found);
                case null -> found.add("is empty.");
            }
            String label = "Operation " + (i + 1)
                    + (operation == null ? "" : " (" + operation.getClass().getSimpleName() + ")");
            found.forEach(problem -> problems.add(label + " " + problem));
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("The blueprint has problems; fix them and send it again:");
            problems.stream().limit(MAX_PROBLEMS_SHOWN).forEach(problem -> message.append("\n- ").append(problem));
            if (problems.size() > MAX_PROBLEMS_SHOWN) {
                message.append("\n- and ").append(problems.size() - MAX_PROBLEMS_SHOWN).append(" more.");
            }
            throw new IllegalArgumentException(message.toString());
        }
        return grid;
    }

    private static void fill(TargetGrid grid, Fill fill, List<String> problems) {
        Box box = box(grid, fill.from(), fill.to(), problems);
        String block = block(fill.block(), "block", problems);
        if (box != null && block != null) {
            PlannedCell cell = place(block, Map.of(), PlannedBlock.Kind.ANY);
            box.forEach((x, y, z) -> grid.set(x, y, z, cell));
        }
    }

    private static void walls(TargetGrid grid, Walls walls, List<String> problems) {
        Box box = box(grid, walls.from(), walls.to(), problems);
        String block = block(walls.block(), "block", problems);
        if (box != null && block != null) {
            PlannedCell cell = place(block, Map.of(), PlannedBlock.Kind.ANY);
            box.forEach((x, y, z) -> {
                if (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ()) {
                    grid.set(x, y, z, cell);
                }
            });
        }
    }

    private static void clear(TargetGrid grid, Clear clear, List<String> problems) {
        Box box = box(grid, clear.from(), clear.to(), problems);
        if (box != null) {
            box.forEach((x, y, z) -> grid.set(x, y, z, PlannedCell.REMOVE));
        }
    }

    private static void door(TargetGrid grid, Door door, List<String> problems) {
        int before = problems.size();
        LocalPos at = door.at();
        if (at == null) {
            problems.add("needs at, where the lower half goes.");
        } else if (inside(grid, at, "at", problems) && !grid.contains(at.x(), at.y() + 1, at.z())) {
            problems.add("has at " + at + ", which leaves no room for the upper half in a blueprint "
                    + grid.height() + " tall.");
        }
        if (door.side() == null) {
            problems.add("needs side: FRONT, BACK, LEFT or RIGHT.");
        }
        String block = block(door.block(), "block", problems);
        if (problems.size() > before) {
            return;
        }
        // A door faces the way someone walks in, as when a player places it from outside.
        String facing = switch (door.side()) {
            case FRONT -> "north";
            case BACK -> "south";
            case LEFT -> "east";
            case RIGHT -> "west";
        };
        grid.set(at.x(), at.y(), at.z(),
                place(block, Map.of("facing", facing, "half", "lower"), PlannedBlock.Kind.DOOR));
        grid.set(at.x(), at.y() + 1, at.z(),
                place(block, Map.of("facing", facing, "half", "upper"), PlannedBlock.Kind.DOOR));
    }

    // Stairs climb from two opposite sides of the covered walls to the ridge, one block in and one block up per
    // layer. The overhang continues that slope outward and down, so the layer at from.y sits on the walls.
    private static void roof(TargetGrid grid, GableRoof roof, List<String> problems) {
        int before = problems.size();
        String stairs = block(roof.stairs(), "stairs", problems);
        LocalPos from = roof.from();
        LocalPos to = roof.to();
        if (from == null || to == null) {
            problems.add("needs both from and to.");
            return;
        }
        if (from.y() != to.y()) {
            problems.add("needs from and to at the same y, just above the walls it covers.");
        }
        int overhang = roof.overhang() == null ? 0 : roof.overhang();
        if (overhang < 0 || overhang > MAX_OVERHANG) {
            problems.add("needs an overhang from 0 to " + MAX_OVERHANG + ".");
        }
        if (problems.size() > before) {
            return;
        }

        int wallMinX = Math.min(from.x(), to.x());
        int wallMaxX = Math.max(from.x(), to.x());
        int wallMinZ = Math.min(from.z(), to.z());
        int wallMaxZ = Math.max(from.z(), to.z());
        int minX = wallMinX - overhang;
        int maxX = wallMaxX + overhang;
        int minZ = wallMinZ - overhang;
        int maxZ = wallMaxZ + overhang;
        RidgeAlong ridgeAlong = roof.ridgeAlong() != null ? roof.ridgeAlong()
                : maxZ - minZ >= maxX - minX ? RidgeAlong.DEPTH : RidgeAlong.WIDTH;
        // With the ridge running along z (DEPTH), the slopes climb across x; otherwise across z.
        boolean acrossX = ridgeAlong == RidgeAlong.DEPTH;
        int low = acrossX ? minX : minZ;
        int high = acrossX ? maxX : maxZ;
        int span = high - low + 1;
        int layers = (span + 1) / 2;
        int bottom = from.y() - overhang;
        int top = bottom + layers - 1;
        if (!grid.contains(minX, bottom, minZ) || !grid.contains(maxX, top, maxZ)) {
            problems.add("needs x " + minX + " to " + maxX + ", y " + bottom + " to " + top + " and z " + minZ + " to "
                    + maxZ + (overhang > 0 ? " with its overhang" : "") + ", " + outside(grid) + ".");
        }
        String ridge = optionalBlock(roof.ridge());
        if (span % 2 == 1 && ridge == null) {
            problems.add("spans an odd " + span + " blocks, so it needs ridge: the block for its top row.");
        }
        if (problems.size() > before) {
            return;
        }

        // A stair faces the way it climbs: toward +x (east, the player's right) or +z (north, away from them).
        PlannedCell lowSlope = place(stairs, Map.of("facing", acrossX ? "east" : "north", "half", "bottom"),
                PlannedBlock.Kind.STAIRS);
        PlannedCell highSlope = place(stairs, Map.of("facing", acrossX ? "west" : "south", "half", "bottom"),
                PlannedBlock.Kind.STAIRS);
        PlannedCell ridgeCell = ridge == null ? null : place(ridge, Map.of(), PlannedBlock.Kind.ANY);
        String gable = optionalBlock(roof.gable());
        PlannedCell gableCell = gable == null ? null : place(gable, Map.of(), PlannedBlock.Kind.ANY);
        int lengthMin = acrossX ? minZ : minX;
        int lengthMax = acrossX ? maxZ : maxX;
        int wallLow = acrossX ? wallMinX : wallMinZ;
        int wallHigh = acrossX ? wallMaxX : wallMaxZ;
        int[] ends = acrossX ? new int[] {wallMinZ, wallMaxZ} : new int[] {wallMinX, wallMaxX};
        for (int layer = 0; layer < layers; layer++) {
            int y = bottom + layer;
            int lowSide = low + layer;
            int highSide = high - layer;
            for (int along = lengthMin; along <= lengthMax; along++) {
                if (lowSide == highSide) {
                    set(grid, acrossX, lowSide, y, along, ridgeCell);
                } else {
                    set(grid, acrossX, lowSide, y, along, lowSlope);
                    set(grid, acrossX, highSide, y, along, highSlope);
                }
            }
            // The gable triangles sit above the end walls, between this layer's two stairs.
            if (gableCell != null && y >= from.y()) {
                int first = Math.max(lowSide + 1, wallLow);
                int last = Math.min(highSide - 1, wallHigh);
                for (int across = first; across <= last; across++) {
                    for (int end : ends) {
                        set(grid, acrossX, across, y, end, gableCell);
                    }
                }
            }
        }
    }

    // across is x when the slopes climb across x, z otherwise; along is the other one.
    private static void set(TargetGrid grid, boolean acrossX, int across, int y, int along, PlannedCell cell) {
        if (acrossX) {
            grid.set(across, y, along, cell);
        } else {
            grid.set(along, y, across, cell);
        }
    }

    private static Box box(TargetGrid grid, LocalPos from, LocalPos to, List<String> problems) {
        if (from == null || to == null) {
            problems.add("needs both from and to.");
            return null;
        }
        boolean fromInside = inside(grid, from, "from", problems);
        boolean toInside = inside(grid, to, "to", problems);
        if (!fromInside || !toInside) {
            return null;
        }
        return new Box(Math.min(from.x(), to.x()), Math.min(from.y(), to.y()), Math.min(from.z(), to.z()),
                Math.max(from.x(), to.x()), Math.max(from.y(), to.y()), Math.max(from.z(), to.z()));
    }

    private static boolean inside(TargetGrid grid, LocalPos pos, String name, List<String> problems) {
        if (grid.contains(pos.x(), pos.y(), pos.z())) {
            return true;
        }
        problems.add("has " + name + " " + pos + ", " + outside(grid) + ".");
        return false;
    }

    private static String outside(TargetGrid grid) {
        return "outside the blueprint, which spans x 0 to " + (grid.width() - 1) + ", y 0 to " + (grid.height() - 1)
                + " and z 0 to " + (grid.depth() - 1);
    }

    private static String block(String block, String name, List<String> problems) {
        if (block == null || block.isBlank()) {
            problems.add("needs " + name + ", a block id such as minecraft:oak_planks.");
            return null;
        }
        return block.trim();
    }

    private static String optionalBlock(String block) {
        return block == null || block.isBlank() ? null : block.trim();
    }

    private static PlannedCell place(String block, Map<String, String> properties, PlannedBlock.Kind kind) {
        return new PlannedCell.Place(new PlannedBlock(block, properties, kind));
    }
}
