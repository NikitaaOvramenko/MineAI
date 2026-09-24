package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.LinkedHashSet;
import java.util.Set;

// The compiled blueprint: one PlannedCell per position, or null to keep what is there.
public final class TargetGrid {
    public interface CellVisitor {
        void visit(LocalPos pos, PlannedCell cell);
    }

    private final int width;
    private final int height;
    private final int depth;
    private final PlannedCell[] cells;

    TargetGrid(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.cells = new PlannedCell[width * height * depth];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth;
    }

    // Null where no operation touched the cell.
    public PlannedCell get(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    void set(int x, int y, int z, PlannedCell cell) {
        cells[index(x, y, z)] = cell;
    }

    // Every cell some operation touched, in x, then y, then z order.
    public void forEachPlanned(CellVisitor visitor) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    PlannedCell cell = cells[index(x, y, z)];
                    if (cell != null) {
                        visitor.visit(new LocalPos(x, y, z), cell);
                    }
                }
            }
        }
    }

    public Set<PlannedBlock> distinctBlocks() {
        Set<PlannedBlock> blocks = new LinkedHashSet<>();
        forEachPlanned((pos, cell) -> {
            if (cell instanceof PlannedCell.Place place) {
                blocks.add(place.block());
            }
        });
        return blocks;
    }

    private int index(int x, int y, int z) {
        if (!contains(x, y, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") is outside the grid");
        }
        return (x * height + y) * depth + z;
    }
}
