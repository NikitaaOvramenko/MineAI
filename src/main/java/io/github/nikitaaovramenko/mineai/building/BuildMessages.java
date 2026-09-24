package io.github.nikitaaovramenko.mineai.building;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.nikitaaovramenko.mineai.blueprint.BuildDiff;
import io.github.nikitaaovramenko.mineai.blueprint.BuildMode;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// What players read about builds in chat.
public final class BuildMessages {
    private static final int MATERIALS_SHOWN = 4;

    private BuildMessages() {}

    public static MutableComponent line(String text) {
        return Component.literal("[MineAi] " + text);
    }

    // The plan, with buttons that run /mineai confirm and /mineai cancel.
    public static Component plan(BuildPlan plan) {
        BuildDiff.Result<BlockState> diff = plan.diff();
        var blueprint = plan.blueprint();
        StringBuilder text = new StringBuilder("Build plan: " + blueprint.width() + " x " + blueprint.height() + " x "
                + blueprint.depth() + ", " + changed(diff) + " (" + materials(diff) + ").");
        if (diff.kept() > 0) {
            text.append(" ").append(diff.kept()).append(" spots already hold something and stay as they are.");
        }
        if (diff.protectedCells() > 0) {
            text.append(" ").append(diff.protectedCells()).append(" containers or unbreakable blocks stay.");
        }
        return line(text.append(" ").toString())
                .append(button("Confirm", ChatFormatting.GREEN, "/mineai confirm", "Build it"))
                .append(" ")
                .append(button("Cancel", ChatFormatting.RED, "/mineai cancel", "Throw the plan away"));
    }

    public static String changed(BuildDiff.Result<BlockState> diff) {
        StringBuilder text = new StringBuilder(diff.placed() + " blocks to place");
        if (diff.replaced() > 0) {
            text.append(", ").append(diff.replaced()).append(" to replace");
        }
        if (diff.removed() > 0) {
            text.append(", ").append(diff.removed()).append(" to clear");
        }
        return text.toString();
    }

    // The most used blocks first, such as "Oak Planks 320, Spruce Stairs 120, Glass Pane 24 and 3 more".
    public static String materials(BuildDiff.Result<BlockState> diff) {
        Map<Block, Long> counts = diff.changes().stream()
                .filter(change -> !change.desired().isAir())
                .collect(Collectors.groupingBy(change -> change.desired().getBlock(), LinkedHashMap::new,
                        Collectors.counting()));
        if (counts.isEmpty()) {
            return "no new blocks";
        }
        List<String> shown = counts.entrySet().stream()
                .sorted(Map.Entry.<Block, Long>comparingByValue().reversed())
                .limit(MATERIALS_SHOWN)
                .map(entry -> entry.getKey().getName().getString() + " " + entry.getValue())
                .toList();
        String more = counts.size() > MATERIALS_SHOWN ? " and " + (counts.size() - MATERIALS_SHOWN) + " more" : "";
        return String.join(", ", shown) + more;
    }

    public static String mode(BuildMode mode) {
        return mode == BuildMode.ADD ? "ADD mode, which only fills empty space"
                : "REPLACE mode, which also replaces and clears blocks";
    }

    private static Component button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover + ": " + command))));
    }
}
