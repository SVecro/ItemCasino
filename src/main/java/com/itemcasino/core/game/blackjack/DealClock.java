package com.itemcasino.core.game.blackjack;

/**
 * When each card of a blackjack hand is allowed on the table, and when the hand's result may be
 * shown.
 *
 * <p>The server deals and settles a hand in a handful of packets that are all true the moment they
 * arrive. This clock is the pacing laid over them: cards land one at a time from the shoe in the
 * order they were dealt, the hole card turns over before the dealer draws to it, and the result
 * waits until the last card has settled. Nothing here decides anything.
 *
 * <p>It lives in the core, with no Minecraft types, because both sides need the same numbers: the
 * client runs it to animate, and the server runs a copy to know how long that animation lasts, so it
 * holds the payout back until the player has seen the cards that decided it.
 */
public final class DealClock {

    /** Ticks between the four opening cards. */
    public static final int DEAL_INTERVAL = 12;
    /** Before a card the player asked for. It should answer the button, not lag behind it. */
    public static final int HIT_INTERVAL = 12;
    /** Between the dealer's own draws: the part of the hand that is watched, so it is not hurried. */
    public static final int DRAW_INTERVAL = 22;
    /** How long a card takes to slide in from the shoe. */
    public static final int SLIDE_TICKS = 8;
    /** How long the hole card takes to turn over. */
    public static final int FLIP_TICKS = 16;
    /** A beat after the hole card is face up, before the dealer draws to it. */
    public static final int REVEAL_PAUSE = 12;
    /** A beat after the last card has settled, before the result is announced. */
    public static final int RESULT_PAUSE = 14;

    /** Two cards each: player, dealer, player, dealer's hole card. */
    public static final int OPENING_CARDS = 4;
    public static final int MAX_CARDS = 32;

    private int tick;
    private int placed;
    private int lastPlaceTick;
    private final int[] placedAt = new int[MAX_CARDS];

    /** Dealing order: {@code order[n]} is the n-th card laid, as a player index or ~dealer index. */
    private final int[] order = new int[MAX_CARDS];
    private int known;
    private int players;
    private int dealers;

    private boolean revealQueued;
    private int holeRevealTick = -1;
    private boolean resultKnown;

    public DealClock() {
        lastPlaceTick = 0;
    }

    /** A new hand: nothing on the table, the first card one deal interval away. */
    public void newHand() {
        placed = 0;
        known = 0;
        players = 0;
        dealers = 0;
        revealQueued = false;
        holeRevealTick = -1;
        resultKnown = false;
        lastPlaceTick = tick;
    }

    /**
     * The server says this many cards are dealt. New cards join the queue in the order a dealer
     * deals them: the opening four interleaved, then the player's own draws, then the dealer's.
     */
    public void dealt(int playerCards, int dealerCards) {
        int p = Math.max(players, Math.min(playerCards, MAX_CARDS));
        int d = Math.max(dealers, Math.min(dealerCards, MAX_CARDS));
        if (known == 0 && p >= 2 && d >= 2) {
            append(0);
            append(~0);
            append(1);
            append(~1);
            players = 2;
            dealers = 2;
        }
        while (players < p) append(players++);
        while (dealers < d) append(~(dealers++));
    }

    private void append(int card) {
        if (known < MAX_CARDS) order[known++] = card;
    }

    /** The hole card has been shown: it turns over once the opening deal is down. */
    public void revealHole() {
        if (holeRevealTick < 0) revealQueued = true;
    }

    /** The hand is over: the result may be shown once the table has caught up. */
    public void resultKnown() {
        resultKnown = true;
    }

    /**
     * One client tick.
     *
     * @return true when a card was laid down this tick
     */
    public boolean tick() {
        tick++;
        if (revealQueued && placed >= Math.min(OPENING_CARDS, known) && tick - lastPlaceTick >= SLIDE_TICKS) {
            revealQueued = false;
            holeRevealTick = tick;
        }
        if (placed >= known) return false;
        // Nothing moves while the hole card is turning, or while it is waiting to: the dealer never
        // draws to a card nobody has seen.
        if (revealQueued && placed >= OPENING_CARDS) return false;
        if (holeRevealTick >= 0 && tick - holeRevealTick < FLIP_TICKS + REVEAL_PAUSE) return false;
        if (tick - lastPlaceTick < intervalFor(placed)) return false;
        placedAt[placed] = tick;
        placed++;
        lastPlaceTick = tick;
        return true;
    }

    private int intervalFor(int ordinal) {
        if (ordinal < OPENING_CARDS) return DEAL_INTERVAL;
        return order[ordinal] < 0 ? DRAW_INTERVAL : HIT_INTERVAL;
    }

    /** Where a card sits in the dealing order, or -1 if it is not on the table. */
    private int ordinalOf(boolean dealer, int index) {
        int wanted = dealer ? ~index : index;
        for (int i = 0; i < known; i++) {
            if (order[i] == wanted) return i;
        }
        return -1;
    }

    /** Ticks this card has been down (fractional with the partial tick), or -1 if it is not down yet. */
    public float age(boolean dealer, int index, float partialTick) {
        int ordinal = ordinalOf(dealer, index);
        if (ordinal < 0 || ordinal >= placed) return -1F;
        return tick + partialTick - placedAt[ordinal];
    }

    /** True once the card has landed, not merely started sliding. */
    public boolean landed(boolean dealer, int index) {
        return age(dealer, index, 0F) >= SLIDE_TICKS;
    }

    /** Ticks since the hole card started turning, or -1. */
    public float holeRevealAge(float partialTick) {
        return holeRevealTick < 0 ? -1F : tick + partialTick - holeRevealTick;
    }

    /** The hole card is to be shown but has not started turning: it is still drawn face down. */
    public boolean holeWaitingToTurn() {
        return revealQueued;
    }

    /** Every dealt card is down and the hole card, if shown, has finished turning. */
    public boolean caughtUp() {
        if (placed < known || revealQueued) return false;
        return holeRevealTick < 0 || tick - holeRevealTick >= FLIP_TICKS;
    }

    /** The result may be announced: everything is down, and a beat has passed. */
    public boolean resultShown() {
        if (!resultKnown || !caughtUp()) return false;
        int settledAt = Math.max(lastPlaceTick + SLIDE_TICKS, holeRevealTick < 0 ? 0 : holeRevealTick + FLIP_TICKS);
        return tick >= settledAt + RESULT_PAUSE;
    }

    public int placed() { return placed; }

    /**
     * How long a client takes to show the end of a hand, from the packet that settles it: the cards it
     * had already seen, the final hand, the hole card turning and the pause before the result.
     */
    public static int revealTicks(int shownPlayer, int shownDealer, int finalPlayer, int finalDealer) {
        DealClock clock = new DealClock();
        clock.dealt(shownPlayer, shownDealer);
        // Whatever was already shown has long since landed.
        for (int i = 0; i < 4 * MAX_CARDS * DRAW_INTERVAL && clock.placed < clock.known; i++) clock.tick();
        for (int i = 0; i < 2 * DRAW_INTERVAL; i++) clock.tick();
        if (shownPlayer + shownDealer == 0) clock.newHand();
        int start = clock.tick;
        clock.dealt(finalPlayer, finalDealer);
        clock.revealHole();
        clock.resultKnown();
        for (int i = 0; i < 10_000 && !clock.resultShown(); i++) clock.tick();
        return clock.tick - start;
    }
}
