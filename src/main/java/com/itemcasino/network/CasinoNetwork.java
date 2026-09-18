package com.itemcasino.network;

import com.itemcasino.ItemCasino;
import com.itemcasino.network.c2s.C2SAnimationComplete;
import com.itemcasino.network.c2s.C2SBlackjackAction;
import com.itemcasino.network.c2s.C2SBlackjackDeal;
import com.itemcasino.network.c2s.C2SCancelWager;
import com.itemcasino.network.c2s.C2SClaimMailbox;
import com.itemcasino.network.c2s.C2SMineAction;
import com.itemcasino.network.c2s.C2SCashierAction;
import com.itemcasino.network.c2s.C2SSetBet;
import com.itemcasino.network.c2s.C2SClaimPayout;
import com.itemcasino.network.c2s.C2SPlaceWager;
import com.itemcasino.network.c2s.C2SRequestStats;
import com.itemcasino.network.c2s.C2SRequestValueTable;
import com.itemcasino.network.c2s.C2SSelectTarget;
import com.itemcasino.network.c2s.C2SSetOption;
import com.itemcasino.network.handler.ServerHandlers;
import com.itemcasino.network.s2c.S2CBlackjackSettled;
import com.itemcasino.network.s2c.S2CBlackjackState;
import com.itemcasino.network.s2c.S2CCoinFlipResult;
import com.itemcasino.network.s2c.S2CDiceResult;
import com.itemcasino.network.s2c.S2COddsQuote;
import com.itemcasino.network.s2c.S2CPayoutReady;
import com.itemcasino.network.s2c.S2CSessionAborted;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.network.s2c.S2CSlotResult;
import com.itemcasino.network.s2c.S2CStats;
import com.itemcasino.network.s2c.S2CVaultDraw;
import com.itemcasino.network.s2c.S2CValueTable;
import com.itemcasino.network.s2c.S2CWheelResult;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * Payload registration.
 *
 * <p>Note the asymmetry: {@code playToServer} takes its handler directly, because the server
 * handler exists on both physical sides. {@code playToClient} takes only the type and the codec;
 * the handlers are attached in {@code CasinoClientNetwork} through
 * {@code RegisterClientPayloadHandlersEvent}, so no client class is ever named from common code and
 * a dedicated server never tries to load one.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class CasinoNetwork {

    /** Bump on any wire change: a mismatched client is then rejected at handshake. */
    public static final String VERSION = "7";

    private CasinoNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToServer(C2SSelectTarget.TYPE, C2SSelectTarget.CODEC,
                ServerHandlers::selectTarget);
        registrar.playToServer(C2SPlaceWager.TYPE, C2SPlaceWager.CODEC,
                ServerHandlers::placeWager);
        registrar.playToServer(C2SCancelWager.TYPE, C2SCancelWager.CODEC,
                ServerHandlers::cancelWager);
        registrar.playToServer(C2SAnimationComplete.TYPE, C2SAnimationComplete.CODEC,
                ServerHandlers::animationComplete);
        registrar.playToServer(C2SClaimPayout.TYPE, C2SClaimPayout.CODEC,
                ServerHandlers::claimPayout);
        registrar.playToServer(C2SBlackjackDeal.TYPE, C2SBlackjackDeal.CODEC,
                ServerHandlers::blackjackDeal);
        registrar.playToServer(C2SBlackjackAction.TYPE, C2SBlackjackAction.CODEC,
                ServerHandlers::blackjackAction);
        registrar.playToServer(C2SRequestValueTable.TYPE, C2SRequestValueTable.CODEC,
                ServerHandlers::requestValueTable);
        registrar.playToServer(C2SSetOption.TYPE, C2SSetOption.CODEC,
                ServerHandlers::setOption);
        registrar.playToServer(C2SRequestStats.TYPE, C2SRequestStats.CODEC,
                ServerHandlers::requestStats);
        registrar.playToServer(C2SClaimMailbox.TYPE, C2SClaimMailbox.CODEC,
                ServerHandlers::claimMailbox);
        registrar.playToServer(C2SMineAction.TYPE, C2SMineAction.CODEC,
                ServerHandlers::mineAction);
        registrar.playToServer(C2SSetBet.TYPE, C2SSetBet.CODEC, ServerHandlers::setBet);
        registrar.playToServer(C2SCashierAction.TYPE, C2SCashierAction.CODEC, ServerHandlers::cashierAction);

        registrar.playToClient(S2COddsQuote.TYPE, S2COddsQuote.CODEC);
        registrar.playToClient(S2CSessionStarted.TYPE, S2CSessionStarted.CODEC);
        registrar.playToClient(S2CWheelResult.TYPE, S2CWheelResult.CODEC);
        registrar.playToClient(S2CDiceResult.TYPE, S2CDiceResult.CODEC);
        registrar.playToClient(S2CCoinFlipResult.TYPE, S2CCoinFlipResult.CODEC);
        registrar.playToClient(S2CSlotResult.TYPE, S2CSlotResult.CODEC);
        registrar.playToClient(S2CVaultDraw.TYPE, S2CVaultDraw.CODEC);
        registrar.playToClient(S2CPayoutReady.TYPE, S2CPayoutReady.CODEC);
        registrar.playToClient(S2CSessionAborted.TYPE, S2CSessionAborted.CODEC);
        registrar.playToClient(S2CBlackjackState.TYPE, S2CBlackjackState.CODEC);
        registrar.playToClient(S2CBlackjackSettled.TYPE, S2CBlackjackSettled.CODEC);
        registrar.playToClient(S2CValueTable.TYPE, S2CValueTable.CODEC);
        registrar.playToClient(S2CStats.TYPE, S2CStats.CODEC);
    }

    /**
     * Null-safe and channel-checked on purpose.
     *
     * <p>Every one of these payloads is presentation: a wheel to spin, a card to turn over, a value
     * table to browse. The authoritative result already lives on the server, so dropping one costs
     * a client an animation and nothing else. Throwing, by contrast, happens <em>inside</em> a
     * settle path — which is how a payout ends up half-delivered.
     *
     * <p>Two listeners can legitimately not have our channel: a mock player driven by a game test
     * or another mod's fake player, and a real player whose connection is already on its way out.
     * NeoForge throws on a send to either, so the check is what keeps them harmless.
     */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || player.connection == null) return;
        if (!player.connection.hasChannel(payload)) return;
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Sends the advisory table used by the target picker. Targetable items only. */
    public static void sendValueTable(ServerPlayer player, ValuationSnapshot snapshot) {
        if (!snapshot.isReady()) return;
        List<S2CValueTable.Entry> entries = new ArrayList<>(1024);
        for (int i = 0; i < snapshot.itemCount(); i++) {
            // targetableAt already excludes unpriced and estimated items.
            if (!snapshot.targetableAt(i)) continue;
            long value = snapshot.valueAt(i);
            entries.add(new S2CValueTable.Entry(
                    BuiltInRegistries.ITEM.getId(snapshot.itemAt(i)), value));
        }
        send(player, new S2CValueTable(entries));
    }
}
