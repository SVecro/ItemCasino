package com.itemcasino.player;

import com.itemcasino.ItemCasino;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Hands a player whatever the casino kept for them, as soon as they are able to carry it. */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class PlayerLifecycle {

    private PlayerLifecycle() {}

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) CasinoMailbox.deliverAndNotify(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) CasinoMailbox.deliverAndNotify(player);
    }

    /** The per-player network bookkeeping would otherwise grow by one entry per player ever seen. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        com.itemcasino.network.RateLimiter.forget(player);
        com.itemcasino.network.handler.ServerHandlers.forget(player);
    }

}
