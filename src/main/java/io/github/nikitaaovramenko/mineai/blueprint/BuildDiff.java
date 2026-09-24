package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

// Compares a TargetGrid with what the world holds and lists the changes a build would make. Generic over
// the game's block-state type, so the rules of each BuildMode are unit-tested without Minecraft.
public final class BuildDiff {
    public interface CellRules<S> {
        S air();

        // Empty enough to build in: air, water, grass.
        boolean canBeReplaced(S state);

        // What ADD mode clears out of a Remove cell: loose things such as grass, but no fluid.
        boolean canClear(S state);

        // What REPLACE mode may overwrite or remove: anything but block entities and unbreakable blocks.
        boolean mayRemove(S state);
    }

    public record Change<S>(LocalPos pos, S previous, S desired) {}

    // kept: cells ADD mode left alone because something was there. protectedCells: cells REPLACE mode left alone.
    public record Result<S>(List<Change<S>> changes, int placed, int replaced, int removed, int kept,
            int protectedCells) {}

    private BuildDiff() {}

    // resolved gives the state a planned block becomes at a position; existing, what is there now.
    public static <S> Result<S> diff(TargetGrid grid, BuildMode mode, BiFunction<PlannedBlock, LocalPos, S> resolved,
            Function<LocalPos, S> existing, CellRules<S> rules) {
        List<Change<S>> changes = new ArrayList<>();
        int placed = 0;
        int replaced = 0;
        int removed = 0;
        int kept = 0;
        int protectedCells = 0;
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                for (int z = 0; z < grid.depth(); z++) {
                    PlannedCell cell = grid.get(x, y, z);
                    if (cell == null) {
                        continue;
                    }
                    LocalPos pos = new LocalPos(x, y, z);
                    boolean remove = cell instanceof PlannedCell.Remove;
                    S desired = cell instanceof PlannedCell.Place place ? resolved.apply(place.block(), pos) : rules.air();
                    S previous = existing.apply(pos);
                    if (Objects.equals(previous, desired)) {
                        continue;
                    }
                    if (!remove && rules.canBeReplaced(previous)) {
                        placed++;
                    } else if (mode == BuildMode.ADD) {
                        if (!remove || !rules.canClear(previous)) {
                            kept++;
                            continue;
                        }
                        removed++;
                    } else if (rules.mayRemove(previous)) {
                        if (remove) {
                            removed++;
                        } else {
                            replaced++;
                        }
                    } else {
                        protectedCells++;
                        continue;
                    }
                    changes.add(new Change<>(pos, previous, desired));
                }
            }
        }
        return new Result<>(changes, placed, replaced, removed, kept, protectedCells);
    }
}
