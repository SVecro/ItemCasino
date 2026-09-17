package com.itemcasino.client;

import com.itemcasino.core.game.blackjack.BlackjackPhase;
import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.core.game.blackjack.DealClock;
import com.itemcasino.core.game.blackjack.Hand;
import com.itemcasino.core.game.blackjack.Outcome;
import com.itemcasino.network.s2c.S2CBlackjackSettled;
import com.itemcasino.network.s2c.S2CBlackjackState;

import java.util.List;

/**
 * What the client knows about the hand in front of it — which is exactly what the server chose to
 * tell it. In particular {@link #dealerVisible()} contains no hole card until the reveal, because
 * the server never sent one.
 *
 * <p>The packets are the truth about what <em>has been</em> dealt; {@link DealClock} decides when
 * each card is allowed on screen. Everything the player reads — the totals, the result, the
 * buttons — follows the cards that have actually landed, never the packet: a total that already
 * knows the dealer's third card while it is still in the shoe gives the hand away.
 */
public final class ClientBlackjackState {

    public static final int SLIDE_TICKS = DealClock.SLIDE_TICKS;
    public static final int FLIP_TICKS = DealClock.FLIP_TICKS;

    private static final DealClock clock = new DealClock();

    private static long handSession = -1;
    private static List<Card> playerHand = List.of();
    private static List<Card> dealerVisible = List.of();
    private static boolean holeHidden = true;
    private static int legalMask;
    private static BlackjackPhase phase = BlackjackPhase.WAITING;
    private static Outcome outcome;
    private static int betUnits = 1;
    private static int deadlineTicks;
    private static boolean acknowledged = true;

    private ClientBlackjackState() {}

    /** A packet for a hand this table has not shown yet starts from an empty felt. */
    private static void startHandIfNew(long sessionId) {
        if (sessionId == handSession) return;
        handSession = sessionId;
        playerHand = List.of();
        dealerVisible = List.of();
        holeHidden = true;
        legalMask = 0;
        outcome = null;
        betUnits = 1;
        acknowledged = true;
        clock.newHand();
    }

    public static void accept(S2CBlackjackState msg) {
        startHandIfNew(msg.sessionId());
        boolean wasHidden = holeHidden;
        playerHand = List.copyOf(msg.playerHand());
        dealerVisible = List.copyOf(msg.dealerVisible());
        holeHidden = msg.holeHidden();
        legalMask = msg.legalMask();
        deadlineTicks = msg.deadlineTicks();
        BlackjackPhase[] phases = BlackjackPhase.values();
        int ordinal = msg.phase();
        phase = (ordinal >= 0 && ordinal < phases.length) ? phases[ordinal] : BlackjackPhase.WAITING;
        outcome = null;
        clock.dealt(playerHand.size(), dealerOnTable());
        if (wasHidden && !holeHidden) clock.revealHole();
    }

    public static void accept(S2CBlackjackSettled msg) {
        startHandIfNew(msg.sessionId());
        playerHand = List.copyOf(msg.playerFinal());
        dealerVisible = List.copyOf(msg.dealerFinal());
        holeHidden = false;
        legalMask = 0;
        betUnits = msg.betUnits();
        phase = BlackjackPhase.SETTLED;
        outcome = Outcome.byId(msg.outcome());
        acknowledged = false;
        clock.dealt(playerHand.size(), dealerVisible.size());
        clock.revealHole();
        clock.resultKnown();
    }

    /**
     * Advances the table clock.
     *
     * @return true when a card was laid down this tick, so the caller can make it audible
     */
    public static boolean tick() {
        if (deadlineTicks > 0) deadlineTicks--;
        return clock.tick();
    }

    /** True exactly once, when the settled hand has been shown in full: the cue to tell the server. */
    public static boolean consumeAcknowledgement() {
        if (acknowledged || !clock.resultShown()) return false;
        acknowledged = true;
        return true;
    }

    public static void reset() {
        handSession = -1;
        playerHand = List.of();
        dealerVisible = List.of();
        holeHidden = true;
        legalMask = 0;
        phase = BlackjackPhase.WAITING;
        outcome = null;
        betUnits = 1;
        deadlineTicks = 0;
        acknowledged = true;
        clock.newHand();
    }

    // ------------------------------------------------------------------ the deal clock

    /** Cards the dealer has on the table, counting the face-down one. */
    public static int dealerOnTable() {
        return dealerVisible.size() + (holeHidden ? 1 : 0);
    }

    /** True once the table has caught up with the packets. */
    public static boolean allPlaced() {
        return clock.caughtUp();
    }

    /** The hand is over and the player has seen every card of it. */
    public static boolean resultShown() {
        return outcome != null && clock.resultShown();
    }

    /** Ticks this card has been on the table, or a negative number if it has not been laid down yet. */
    public static float placementAge(boolean dealer, int index, float partialTick) {
        return clock.age(dealer, index, partialTick);
    }

    /** Ticks since the hole card started turning over, or -1 if it has not. */
    public static float holeRevealAge(float partialTick) {
        return clock.holeRevealAge(partialTick);
    }

    /** The hole card has been shown by the server but has not started turning on screen. */
    public static boolean holeWaitingToTurn() {
        return clock.holeWaitingToTurn();
    }

    /** The dealer's second card is still face down on screen, whatever the packet says. */
    public static boolean holeFaceDown(float partialTick) {
        if (holeHidden) return true;
        if (clock.holeWaitingToTurn()) return true;
        float age = clock.holeRevealAge(partialTick);
        return age >= 0F && age < FLIP_TICKS / 2F;
    }

    /** The player's total over the cards that have landed. */
    public static int shownPlayerTotal() {
        Hand hand = new Hand();
        for (int i = 0; i < playerHand.size(); i++) {
            if (clock.landed(false, i)) hand.add(playerHand.get(i));
        }
        return hand.total();
    }

    /** The dealer's total over the cards that have landed face up. */
    public static int shownDealerTotal() {
        Hand hand = new Hand();
        for (int i = 0; i < dealerVisible.size(); i++) {
            if (i == 1 && holeFaceDown(0F)) continue;
            if (clock.landed(true, i)) hand.add(dealerVisible.get(i));
        }
        return hand.total();
    }

    /** A face-down card is on the table, so the dealer's total is not the whole story. */
    public static boolean dealerHasHiddenCard() {
        return clock.landed(true, 1) && holeFaceDown(0F);
    }

    /** At least one card has landed in front of the player. */
    public static boolean anyLanded() {
        return !playerHand.isEmpty() && clock.landed(false, 0);
    }

    // ------------------------------------------------------------------ accessors

    public static List<Card> playerHand() { return playerHand; }

    public static List<Card> dealerVisible() { return dealerVisible; }

    public static boolean holeHidden() { return holeHidden; }

    public static int legalMask() { return legalMask; }

    public static BlackjackPhase phase() { return phase; }

    public static Outcome outcome() { return outcome; }

    public static int betUnits() { return betUnits; }

    public static int deadlineTicks() { return deadlineTicks; }

    public static long handSession() { return handSession; }
}
