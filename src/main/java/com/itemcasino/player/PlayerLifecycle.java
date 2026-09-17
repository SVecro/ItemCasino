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
}
