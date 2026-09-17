package com.itemcasino.network.handler;

import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.blackjack.BlackjackAction;
import com.itemcasino.menu.AbstractCasinoMenu;
import com.itemcasino.network.CasinoNetwork;
import com.itemcasino.network.RateLimiter;
import com.itemcasino.network.c2s.C2SAnimationComplete;
import com.itemcasino.network.c2s.C2SBlackjackAction;
import com.itemcasino.network.c2s.C2SBlackjackDeal;
import com.itemcasino.network.c2s.C2SCancelWager;
import com.itemcasino.network.c2s.C2SClaimMailbox;
import com.itemcasino.network.c2s.C2SMineAction;
import com.itemcasino.network.c2s.C2SCashierAction;
import com.itemcasino.network.c2s.C2SSetBet;
import com.itemcasino.menu.CashierMenu;
import com.itemcasino.network.c2s.C2SClaimPayout;
import com.itemcasino.network.c2s.C2SPlaceWager;
import com.itemcasino.network.c2s.C2SRequestStats;
import com.itemcasino.network.c2s.C2SRequestValueTable;
import com.itemcasino.network.c2s.C2SSelectTarget;
import com.itemcasino.network.c2s.C2SSetOption;
import com.itemcasino.jackpot.Jackpot;
import com.itemcasino.network.s2c.S2CStats;
import com.itemcasino.player.CasinoMailbox;
import com.itemcasino.player.CasinoStats;
import com.itemcasino.session.BlackjackSession;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.CoinFlipSession;
import com.itemcasino.session.MineFieldSession;
import com.itemcasino.session.SlotMachineSession;
import com.itemcasino.session.VaultSession;
import com.itemcasino.session.DiceSession;
import com.itemcasino.session.UpgraderSession;
import com.itemcasino.valuation.ValuationEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Every client-to-server handler in the mod.
 *
 * <p>Seven guards run on every packet, in this order, through {@link #resolve}. Factoring them into
 * one place is the point: a handler that forgets one is a duplication bug, and "remember to check
 * the container id" is not a strategy.
 *
 * <ol>
 *   <li>the sender really is a {@link ServerPlayer}</li>
 *   <li>the rate limit is not exhausted</li>
 *   <li>the container id matches the menu the player actually has open</li>
 *   <li>that menu is a casino menu</li>
 *   <li>it is bound to a live session</li>
 *   <li>the session is still valid (block present and in reach, or pocket session alive)</li>
 *   <li><strong>the sender holds the seat</strong> — spectators may watch, never act</li>
 * </ol>
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    private record Ctx(ServerPlayer player, AbstractCasinoMenu menu, CasinoSession session) {}

    private static Optional<Ctx> resolve(IPayloadContext context, int containerId) {
        if (!(context.player() instanceof ServerPlayer player)) return Optional.empty();

        if (!RateLimiter.allow(player)) {
            if (RateLimiter.isFlooding(player)) {
                context.disconnect(Component.translatable("itemcasino.net.flood"));
            }
            return Optional.empty();
        }

        AbstractContainerMenu open = player.containerMenu;
        if (open == null || open.containerId != containerId) return Optional.empty();
        if (!(open instanceof AbstractCasinoMenu menu)) return Optional.empty();

        CasinoSession session = menu.session();
        if (session == null || !session.stillValid(player)) return Optional.empty();
        if (!session.isSeated(player)) return Optional.empty();

        session.touch();
        return Optional.of(new Ctx(player, menu, session));
    }

    private static void on(IPayloadContext context, Runnable body, String what) {
        context.enqueueWork(body).exceptionally(error -> {
            ItemCasino.LOGGER.error("Casino packet '{}' failed", what, error);
            return null;
        });
    }

    // ------------------------------------------------------------------ handlers

    public static void selectTarget(C2SSelectTarget msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            if (ctx.session() instanceof UpgraderSession upgrader) {
                upgrader.selectTarget(ctx.player(), msg.target());
            }
        }), "select_target");
    }

    public static void placeWager(C2SPlaceWager msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            if (ctx.session() instanceof UpgraderSession upgrader) upgrader.placeWager(ctx.player());
            else if (ctx.session() instanceof DiceSession dice) dice.placeWager(ctx.player());
            else if (ctx.session() instanceof BlackjackSession blackjack) blackjack.placeWager(ctx.player());
            else if (ctx.session() instanceof CoinFlipSession duel) duel.placeWager(ctx.player());
            else if (ctx.session() instanceof SlotMachineSession slots) slots.placeWager(ctx.player());
            else if (ctx.session() instanceof VaultSession vault) vault.placeWager(ctx.player());
            else if (ctx.session() instanceof MineFieldSession field) field.placeWager(ctx.player());
        }), "place_wager");
    }

    public static void cancelWager(C2SCancelWager msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            // Nothing is escrowed in ARMED, so there is nothing to give back; this only clears the
            // chosen target so the confirm button greys out again.
            if (ctx.session() instanceof UpgraderSession upgrader) upgrader.clearTarget();
        }), "cancel_wager");
    }

    public static void animationComplete(C2SAnimationComplete msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            // The acknowledgement only ever brings a settle forward, and only once most of the
            // animation has had time to play. Without this gate a modified client answered on the
            // very next tick and span as fast as the rate limiter allowed. Refusing it costs nothing:
            // the deadline settles the wager a moment later.
            if (!ctx.session().acknowledgementDue()) return;
            if (ctx.session() instanceof UpgraderSession upgrader) upgrader.finishSpin(msg.sessionId());
            else if (ctx.session() instanceof DiceSession dice) dice.finishRoll(msg.sessionId());
            else if (ctx.session() instanceof CoinFlipSession duel) duel.finishFlip(msg.sessionId());
            else if (ctx.session() instanceof SlotMachineSession slots) slots.finishSpin(msg.sessionId());
            else if (ctx.session() instanceof VaultSession vault) vault.finishDraw(msg.sessionId());
            else if (ctx.session() instanceof BlackjackSession blackjack) blackjack.finishReveal(msg.sessionId());
        }), "animation_complete");
    }

    public static void blackjackDeal(C2SBlackjackDeal msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            if (ctx.session() instanceof BlackjackSession blackjack) blackjack.deal(ctx.player());
        }), "blackjack_deal");
    }

    public static void blackjackAction(C2SBlackjackAction msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            if (ctx.session() instanceof BlackjackSession blackjack) {
                // byId returns null outside the enum, and act() re-derives legality from the
                // server's own hand rather than the client's mask.
                blackjack.act(ctx.player(), msg.sessionId(), BlackjackAction.byId(msg.action()));
            }
        }), "blackjack_action");
    }

    public static void mineAction(C2SMineAction msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            if (!(ctx.session() instanceof MineFieldSession field)) return;
            // Legality -- the board, the tile, the token -- is re-derived by the session itself.
            if (msg.action() == C2SMineAction.REVEAL) field.reveal(ctx.player(), msg.token(), msg.tile());
            else if (msg.action() == C2SMineAction.CASH_OUT) field.cashOut(ctx.player(), msg.token());
        }), "mine_action");
    }

    public static void setBet(C2SSetBet msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx ->
                ctx.session().setBet(ctx.player(), msg.chips())), "set_bet");
    }

    /**
     * The cashier has no session, so it gets the guards that apply to it by hand: a real server
     * player, the rate limit, and the container id of the counter they actually have open.
     */
    public static void cashierAction(C2SCashierAction msg, IPayloadContext context) {
        on(context, () -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!RateLimiter.allow(player)) return;
            if (!(player.containerMenu instanceof CashierMenu menu)) return;
            if (menu.containerId != msg.containerId() || !menu.stillValid(player)) return;
            if (msg.action() == C2SCashierAction.DEPOSIT) menu.deposit(player);
            else if (msg.action() == C2SCashierAction.WITHDRAW) menu.withdraw(player, msg.currency(), msg.count());
        }, "cashier_action");
    }

    public static void setOption(C2SSetOption msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx ->
                ctx.session().setOption(ctx.player(), msg.option())), "set_option");
    }

    public static void claimPayout(C2SClaimPayout msg, IPayloadContext context) {
        on(context, () -> resolve(context, msg.containerId()).ifPresent(ctx -> {
            CasinoSession session = ctx.session();
            if (!session.hasPayout()) return;
            if (msg.sessionId() != 0 && msg.sessionId() != session.sessionId()) return;
            session.deliverPayout(ctx.player());
        }), "claim_payout");
    }

    /** The sender's own stats. Answered for the sender only; there is nothing to address. */
    public static void requestStats(C2SRequestStats msg, IPayloadContext context) {
        on(context, () -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!RateLimiter.allow(player)) return;
            sendStats(player);
        }, "request_stats");
    }

    public static void claimMailbox(C2SClaimMailbox msg, IPayloadContext context) {
        on(context, () -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!RateLimiter.allow(player)) return;
            CasinoMailbox.deliverAndNotify(player);
            sendStats(player);
        }, "claim_mailbox");
    }

    private static void sendStats(ServerPlayer player) {
        var server = player.level().getServer();
        CasinoStats.Entry entry = CasinoStats.of(server).get(player.getUUID());
        long pending = CasinoMailbox.of(server).pendingCount(player.getUUID());
        long pot = Jackpot.valueOf(player.level());
        CasinoNetwork.send(player, S2CStats.of(entry, pending, pot));
    }

    public static void requestValueTable(C2SRequestValueTable msg, IPayloadContext context) {
        on(context, () -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!RateLimiter.allow(player)) return;
            CasinoNetwork.sendValueTable(player, ValuationEngine.snapshot());
        }, "request_value_table");
    }
}
