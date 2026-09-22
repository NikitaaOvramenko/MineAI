package io.github.nikitaaovramenko.mineai.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import io.github.nikitaaovramenko.mineai.blueprint.Blueprint;
import io.github.nikitaaovramenko.mineai.blueprint.BuildDiff;
import io.github.nikitaaovramenko.mineai.blueprint.BuildMode;
import io.github.nikitaaovramenko.mineai.building.BuildMessages;
import io.github.nikitaaovramenko.mineai.building.BuildPlan;
import io.github.nikitaaovramenko.mineai.building.BuildPlanner;
import io.github.nikitaaovramenko.mineai.building.PendingBuilds;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

// Building: the model describes a building as a Blueprint, planBuild shows the player its outline, and nothing
// changes until the player confirms with /mineai confirm (see the building package). Needs /fill rights.
public class BuildTools {
    private static final int MAX_BLOCK_MATCHES = 40;

    private final ToolContext context;

    public BuildTools(ToolContext context) {
        this.context = context;
    }

    // What planBuild was given, so the model can change it and send it back.
    public record PendingBuild(Blueprint blueprint, BuildMode mode, int distance, int raise) {}

    @Tool("Plans a building in front of the player from a blueprint and shows the player its outline. Nothing is"
            + " built until the player confirms, and a finished build can be undone. Use it whenever the player asks"
            + " you to build something. A new plan replaces the one waiting, at the same spot unless moveToPlayer is"
            + " true; to change the waiting plan, get it with getPendingBuild, edit it and send it all again.")
    public String planBuild(
            @P("The building") Blueprint blueprint,
            @P(value = "ADD (the default) builds only into empty space and never breaks a block; REPLACE also"
                    + " replaces and clears blocks to match the blueprint exactly, except containers",
                    defaultValue = "ADD") BuildMode mode,
            @P(value = "How many blocks in front of the player the building's near side is; default 2",
                    defaultValue = "2") int distance,
            @P(value = "How many blocks above the player's feet the building's bottom layer is; negative sinks it into"
                    + " the ground; default 0", defaultValue = "0") int raise,
            @P(value = "true to put the plan in front of the player even if one is waiting elsewhere; default false",
                    defaultValue = "false") boolean moveToPlayer) {
        requireBuildRights();
        BuildPlan waiting = PendingBuilds.get(context.playerId()).orElse(null);
        // A changed plan stays where the player saw the old one, however they have moved since.
        RequestOrigin origin = waiting != null && !moveToPlayer ? waiting.origin() : context.origin();
        ServerLevel level = context.server().getLevel(origin.dimension());
        if (level == null) {
            throw new IllegalStateException("The dimension the player was in is gone.");
        }
        BuildPlan plan = BuildPlanner.plan(level, context.playerId(), origin, blueprint, mode, distance, raise);
        if (plan.diff().changes().isEmpty()) {
            return "That blueprint would change nothing there, so nothing was planned: every block it asks for is"
                    + " already there" + (mode == BuildMode.ADD ? " or the space is taken, and ADD mode never builds"
                    + " over blocks" : "") + ".";
        }
        PendingBuilds.put(plan);
        context.player().sendSystemMessage(BuildMessages.plan(plan));
        return summary(level, plan);
    }

    @Tool("Returns the building plan waiting for the player's confirmation, as the blueprint and settings planBuild"
            + " was given. Use it when the player wants the plan changed: edit it and send it to planBuild again,"
            + " which keeps the building where the player saw it.")
    public PendingBuild getPendingBuild() {
        requireBuildRights();
        BuildPlan plan = PendingBuilds.get(context.playerId()).orElseThrow(
                () -> new IllegalStateException("No building plan is waiting for this player's confirmation."));
        return new PendingBuild(plan.blueprint(), plan.mode(), plan.distance(), plan.raise());
    }

    @Tool("Finds block ids by the words in them, including blocks from other mods: \"cherry\" finds every cherry"
            + " block, \"stairs\" every kind of stairs. Use it when unsure whether a block exists or what it is called.")
    public String findBlocks(@P("Words the id must contain, such as cherry or oak stairs") String query) {
        List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s_:]+"))
                .filter(word -> !word.isBlank())
                .toList();
        List<String> matches = BuiltInRegistries.BLOCK.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(id -> words.stream().allMatch(id::contains))
                .sorted()
                .toList();
        if (matches.isEmpty()) {
            return "No block id contains " + String.join(" and ", words) + ".";
        }
        String shown = String.join(", ", matches.subList(0, Math.min(MAX_BLOCK_MATCHES, matches.size())));
        return matches.size() > MAX_BLOCK_MATCHES
                ? shown + ", and " + (matches.size() - MAX_BLOCK_MATCHES) + " more; add words to narrow it down."
                : shown;
    }

    // Building is what /fill does, so the same players may do it. Checked before anything else, because a
    // plan's summary tells what the area holds.
    private void requireBuildRights() {
        if (!context.canUseCommand("fill")) {
            throw new IllegalStateException("This player is not allowed to build: that takes /fill, so cheats or"
                    + " operator rights.");
        }
    }

    private static String summary(ServerLevel level, BuildPlan plan) {
        BoundingBox box = plan.bounds();
        BuildDiff.Result<BlockState> diff = plan.diff();
        Blueprint blueprint = plan.blueprint();
        StringBuilder text = new StringBuilder("Planned a " + blueprint.width() + " x " + blueprint.height() + " x "
                + blueprint.depth() + " building facing " + plan.frame().forward().getName() + ", from x="
                + box.minX() + ", y=" + box.minY() + ", z=" + box.minZ() + " to x=" + box.maxX() + ", y=" + box.maxY()
                + ", z=" + box.maxZ() + ", in " + BuildMessages.mode(plan.mode()) + ": "
                + BuildMessages.changed(diff) + " (" + BuildMessages.materials(diff) + ").");
        if (diff.kept() > 0) {
            text.append(" ").append(diff.kept()).append(" spots already hold blocks, which ADD mode leaves;"
                    + " REPLACE mode would build over them.");
        }
        if (diff.protectedCells() > 0) {
            text.append(" ").append(diff.protectedCells()).append(" containers or unbreakable blocks stay as they are.");
        }
        List<Entity> inside = level.getEntities((Entity) null, AABB.of(box), entity -> entity instanceof LivingEntity);
        long players = inside.stream().filter(entity -> entity instanceof Player).count();
        if (players > 0) {
            text.append(" The player").append(players > 1 ? "s " : " ").append("inside the area will block the"
                    + " spots they stand in.");
        }
        if (inside.size() > players) {
            text.append(" ").append(inside.size() - players).append(" mobs are inside the area.");
        }
        return text.append(" Nothing is built yet: the player sees the outline and confirms with the [Confirm]"
                + " button in chat or /mineai confirm; /mineai cancel drops the plan and /mineai undo takes back a"
                + " finished build. Tell them so briefly.").toString();
    }
}
