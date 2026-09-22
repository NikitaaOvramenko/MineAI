package io.github.nikitaaovramenko.mineai.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

// Where the player stood and which way they faced when they sent /ai. Captured before the model starts
// thinking, so "in front of me" means where they were when they asked, however they move meanwhile.
public record RequestOrigin(ResourceKey<Level> dimension, BlockPos position, Direction facing) {
    public static RequestOrigin of(ServerPlayer player) {
        return new RequestOrigin(player.serverLevel().dimension(), player.blockPosition(), player.getDirection());
    }
}
