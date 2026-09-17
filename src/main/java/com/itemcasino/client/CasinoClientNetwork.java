package com.itemcasino.client;

import com.itemcasino.ItemCasino;
import com.itemcasino.network.s2c.S2CBlackjackSettled;
import com.itemcasino.network.s2c.S2CBlackjackState;
import com.itemcasino.network.s2c.S2CCoinFlipResult;
import com.itemcasino.network.s2c.S2CDiceResult;
import com.itemcasino.network.s2c.S2COddsQuote;
import com.itemcasino.network.s2c.S2CPayoutReady;
import com.itemcasino.network.s2c.S2CSessionAborted;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.network.s2c.S2CSlotResult;
import com.itemcasino.network.s2c.S2CVaultDraw;
import com.itemcasino.network.s2c.S2CValueTable;
import com.itemcasino.network.s2c.S2CWheelResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * Attaches the client handlers to the payload types declared in {@code CasinoNetwork}.
 *
 * <p>This is the half of the split registration that only exists on a physical client: common code
 * registers the type and codec so a dedicated server can encode and send, and this class — which a
 * dedicated server never loads — supplies the handlers.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID, value = Dist.CLIENT)
public final class CasinoClientNetwork {

    private CasinoClientNetwork() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(S2COddsQuote.TYPE, ClientPayloadHandlers::oddsQuote);
        event.register(S2CSessionStarted.TYPE, ClientPayloadHandlers::sessionStarted);
        event.register(S2CWheelResult.TYPE, ClientPayloadHandlers::wheelResult);
        event.register(S2CDiceResult.TYPE, ClientPayloadHandlers::diceResult);
        event.register(S2CCoinFlipResult.TYPE, ClientPayloadHandlers::coinFlipResult);
        event.register(S2CSlotResult.TYPE, ClientPayloadHandlers::slotResult);
        event.register(S2CVaultDraw.TYPE, ClientPayloadHandlers::vaultDraw);
        event.register(S2CBlackjackState.TYPE, ClientPayloadHandlers::blackjackState);
        event.register(S2CBlackjackSettled.TYPE, ClientPayloadHandlers::blackjackSettled);
        event.register(S2CPayoutReady.TYPE, ClientPayloadHandlers::payoutReady);
        event.register(S2CSessionAborted.TYPE, ClientPayloadHandlers::sessionAborted);
        event.register(S2CValueTable.TYPE, ClientPayloadHandlers::valueTable);
        event.register(com.itemcasino.network.s2c.S2CStats.TYPE, ClientPayloadHandlers::stats);
    }
}
