package io.github.nikitaaovramenko.mineai.tools;

import java.util.UUID;

import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// What a tool can see of the /ai request that called it. Tool methods run on the server thread, so
// they may use the server, its levels and the player directly.
public record ToolContext(MinecraftServer server, UUID playerId, RequestOrigin origin) {
    // Looked up on every call: the player may have left while the model was thinking.
    public ServerPlayer player() {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            throw new IllegalStateException("The player who asked is no longer online.");
        }
        return player;
    }

    public boolean canUseCommand(String name) {
        return canUseCommand(player().createCommandSourceStack(), name);
    }

    // Asks the server's own command tree, so a tool never tells a player more than typing the command
    // would, whatever permission rules the server applies to it.
    public static boolean canUseCommand(CommandSourceStack source, String name) {
        CommandNode<CommandSourceStack> command = source.getServer().getCommands().getDispatcher().getRoot().getChild(name);
        return command != null && command.canUse(source);
    }
}
