package io.github.nikitaaovramenko.mineai.building;

import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.nikitaaovramenko.mineai.MineAi;
import io.github.nikitaaovramenko.mineai.tools.ToolContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// /mineai confirm, cancel and undo: what the buttons under a build plan run. Plain commands rather than
// tools, so confirming never depends on how the model reads "yes".
@EventBusSubscriber(modid = MineAi.MODID)
public final class BuildCommands {
    private BuildCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mineai")
                // Building is what /fill does, so the same players may do it.
                .requires(source -> ToolContext.canUseCommand(source, "fill"))
                .then(Commands.literal("confirm").executes(context -> confirm(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
                .then(Commands.literal("undo").executes(context -> undo(context.getSource()))));
    }

    private static int confirm(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID playerId = player.getUUID();
        if (BuildJobs.isRunning(playerId)) {
            return fail(source, "Your last build is still going. Wait for it to finish.");
        }
        BuildPlan plan = PendingBuilds.get(playerId).orElse(null);
        if (plan == null) {
            return fail(source, "There is no build plan to confirm. Ask /ai to build something first.");
        }
        ServerLevel level = player.serverLevel();
        if (!isNear(level, plan.frame().dimension() == level.dimension(), plan.bounds())) {
            return fail(source, "Go back near the planned building to confirm it.");
        }
        PendingBuilds.take(playerId);
        BuildJobs.build(plan, level);
        source.sendSuccess(() -> BuildMessages.line("Building " + plan.diff().changes().size() + " blocks..."), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) throws CommandSyntaxException {
        if (PendingBuilds.take(source.getPlayerOrException().getUUID()).isEmpty()) {
            return fail(source, "There is no build plan to cancel.");
        }
        source.sendSuccess(() -> BuildMessages.line("Build plan thrown away."), false);
        return 1;
    }

    private static int undo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID playerId = player.getUUID();
        if (BuildJobs.isRunning(playerId)) {
            return fail(source, "Wait for the current build to finish first.");
        }
        BuildHistory.Entry entry = BuildHistory.pop(playerId).orElse(null);
        if (entry == null) {
            return fail(source, "There is no build of yours to undo. Builds are forgotten when the server stops.");
        }
        ServerLevel level = player.serverLevel();
        if (!isNear(level, entry.dimension() == level.dimension(), entry.bounds())) {
            BuildHistory.push(playerId, entry);
            return fail(source, "Go back near your last build to undo it.");
        }
        BuildJobs.undo(playerId, entry);
        source.sendSuccess(() -> BuildMessages.line("Undoing your last build..."), false);
        return 1;
    }

    // In the player's dimension, with all of it loaded, so no block of it is skipped for being unloaded.
    private static boolean isNear(ServerLevel level, boolean sameDimension, BoundingBox bounds) {
        if (!sameDimension) {
            return false;
        }
        try {
            BuildPlanner.requireInWorld(level, bounds);
            return true;
        } catch (IllegalArgumentException notLoaded) {
            return false;
        }
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(BuildMessages.line(message));
        return 0;
    }
}
