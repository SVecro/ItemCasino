package com.itemcasino.client;

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
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client handlers. Every one of them only updates presentation state — no inventory is touched,
 * no outcome is computed, nothing is sent back except the cosmetic animation acknowledgement.
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}


    public static void oddsQuote(S2COddsQuote msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientSessionState.quote(msg.containerId(), msg.target(),
                msg.oddsPpm(), msg.inputValue(), msg.targetValue()));
    }

    public static void sessionStarted(S2CSessionStarted msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientSessionState.beginSession(msg.containerId(), msg.sessionId());
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
        });
    }

    public static void wheelResult(S2CWheelResult msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientSessionState.beginSpin(msg.containerId(), msg.sessionId(),
                msg.win(), msg.stopAngle(), msg.spinTicks(), 8));
    }

    /** The dice: the counter runs to a roll the server has already made. */
    public static void diceResult(S2CDiceResult msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientSessionState.beginRoll(msg.containerId(), msg.sessionId(),
                msg.win(), msg.roll(), msg.rollTicks()));
    }

    /**
     * The duel. The coin always lands showing the winner's colour, so the stop angle is chosen from
     * the winning seat rather than from whether this particular client won.
     */
    public static void coinFlipResult(S2CCoinFlipResult msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientSessionState.beginDuel(msg.containerId(), msg.sessionId(),
                msg.winnerSeat(), msg.spinTicks()));
    }

    /**
     * The reels. The generic spin state is started for the full run of the animation, stagger
     * included, so the acknowledgement that lets the server settle does not arrive while the third
     * reel is still turning.
     */
    public static void slotResult(S2CSlotResult msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientSlotState.begin(msg.sessionId(), msg.left(), msg.middle(), msg.right(),
                    msg.spinTicks(), msg.stagger());
            ClientSessionState.beginSpin(msg.containerId(), msg.sessionId(), false, 0F,
                    msg.spinTicks() + 2 * msg.stagger(), 0);
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7F);
        });
    }

    /**
     * A draw at the Vault. The pointer is landed against the same widened arc the screen paints,
     * so a win always stops on gold -- the widening is cosmetic, but a pointer that disagreed with
     * it would look exactly like the bug the wheel already had once.
     */
    public static void vaultDraw(S2CVaultDraw msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            int shown = com.itemcasino.client.screen.VaultScreen.displayPpm(msg.drawPpm());
            float stop = com.itemcasino.core.game.wheel.WheelMath.stopAngle(msg.won(), shown, 0.5F);
            ClientSessionState.quote(msg.containerId(), null, msg.drawPpm(), -1L, -1L);
            ClientSessionState.beginSpin(msg.containerId(), msg.sessionId(), msg.won(), stop,
                    msg.spinTicks(), 6);
            // The same click either way: a fanfare here would announce the draw before the dial did.
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8F);
        });
    }

    public static void blackjackState(S2CBlackjackState msg, IPayloadContext context) {
        // No sound here: the table lays the cards down one at a time and makes its own noise as
        // each lands, so a click on arrival would fire once for a packet carrying four cards.
        context.enqueueWork(() -> ClientBlackjackState.accept(msg));
    }

    public static void blackjackSettled(S2CBlackjackSettled msg, IPayloadContext context) {
        // No sound and no payout: the table shows the end of the hand first, and the payout that
        // follows it is revealed by the screen once the last card is down.
        context.enqueueWork(() -> ClientBlackjackState.accept(msg));
    }

    public static void payoutReady(S2CPayoutReady msg, IPayloadContext context) {
        // The sound belongs to the reveal, which the screen times to the end of its animation.
        context.enqueueWork(() -> ClientSessionState.payoutReady(msg.sessionId(), msg.payout(),
                msg.winnerSeat(), msg.tier(), msg.winCents()));
    }

    public static void sessionAborted(S2CSessionAborted msg, IPayloadContext context) {
        context.enqueueWork(ClientSessionState::abort);
    }

    public static void stats(com.itemcasino.network.s2c.S2CStats msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientStatsState.accept(msg));
    }

    public static void valueTable(S2CValueTable msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientValueCache.accept(msg));
    }

    private static void playSound(net.minecraft.sounds.SoundEvent event, float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.player.playSound(event, 0.6F, pitch);
    }
}
