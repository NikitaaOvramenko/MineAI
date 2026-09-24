package io.github.nikitaaovramenko.mineai.blueprint;

// What the blueprint wants in one cell. A cell no operation touched holds null: keep whatever is there.
public sealed interface PlannedCell {
    Remove REMOVE = new Remove();

    // This block must be here.
    record Place(PlannedBlock block) implements PlannedCell {}

    // This cell must be empty.
    record Remove() implements PlannedCell {}
}
