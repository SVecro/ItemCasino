package com.itemcasino;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Game-bus handlers that protect a live wager.
 *
 * <p>Breaking the table is the most direct route to duplicating or voiding an escrow, so it is
 * handled here rather than in a block override: {@code BlockEvent.BreakEvent} is stable across the
 * 1.21 line, fires before anything is removed, and can be cancelled.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class CasinoEvents {

    private CasinoEvents() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof AbstractCasinoBlockEntity table)) return;
        if (!table.hasLiveWager()) return;

        if (CasinoConfig.SERVER.protectBlockDuringWager.get()) {
            event.setCanceled(true);
            if (event.getPlayer() != null) {
                event.getPlayer().displayClientMessage(
                        Component.translatable("itemcasino.message.wager_in_progress"), true);
            }
        }
        // Not cancelled: nothing to do here. The block entity's preRemoveSideEffects settles and
        // hands everything back for this removal and for every other kind (explosions,
        // /setblock, other mods), which this player-only event never sees.
    }
}
