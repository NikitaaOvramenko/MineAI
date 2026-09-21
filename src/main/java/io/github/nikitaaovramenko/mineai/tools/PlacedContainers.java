package io.github.nikitaaovramenko.mineai.tools;

import java.util.UUID;
import java.util.function.Supplier;

import io.github.nikitaaovramenko.mineai.MineAi;

import net.minecraft.Util;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

// Minecraft doesn't record who placed a block or a cart, so this does it for the storage StorageTools
// lists: placed storage gets a data attachment, saved with its block entity or entity and gone once it is
// broken. Storage without one came from world generation, commands, or was placed before MineAi tracked
// placements. (Chested animals need none: only a tamed animal takes a chest, so it has an owner.)
@EventBusSubscriber(modid = MineAi.MODID)
public final class PlacedContainers {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineAi.MODID);
    // The placer's UUID, or the nil UUID when the game doesn't say who placed it. The default only
    // satisfies the API: nothing reads this attachment where it wasn't set.
    private static final Supplier<AttachmentType<UUID>> PLACED_BY = ATTACHMENT_TYPES.register("placed_by",
            () -> AttachmentType.builder(() -> Util.NIL_UUID).serialize(UUIDUtil.CODEC).build());

    private PlacedContainers() {}

    static boolean placedByPlayer(IAttachmentHolder storage) {
        return storage.hasData(PLACED_BY);
    }

    // Fires once the block and its block entity exist; if another mod cancels the placement, both go.
    @SubscribeEvent
    static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        BlockEntity blockEntity = event.getLevel().getBlockEntity(event.getPos());
        if (StorageTools.isStorage(blockEntity)) {
            blockEntity.setData(PLACED_BY, player.getUUID());
        }
    }

    // Entities have no place event. A chest minecart or boat that joins the level new rather than loaded
    // from disk was just made by a player, a dispenser or a command. World generation adds its mineshaft
    // minecarts the same way, but always with a loot table, which those never give it.
    @SubscribeEvent
    static void onEntityJoined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && !event.loadedFromDisk() && StorageTools.isStorage(event.getEntity())
                && event.getEntity() instanceof ContainerEntity container && container.getLootTable() == null) {
            event.getEntity().setData(PLACED_BY, Util.NIL_UUID);
        }
    }
}
