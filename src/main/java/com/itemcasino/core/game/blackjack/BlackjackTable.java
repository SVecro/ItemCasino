package com.itemcasino.core.game.blackjack;

import com.itemcasino.core.game.Roller;

/**
 * The blackjack rules engine: a deterministic state machine over a {@link Shoe}.
 *
 * <p>Contains no Minecraft types and no item logic at all — it deals in "bet units", and the
 * server-side session turns those into stacks. That separation is what makes the exhaustive
 * legal-action tests and the multi-million-hand RTP simulation possible without a game instance.
 *
 * <p>Every method that mutates state is guarded by the phase; an illegal call returns {@code false}
 * and changes nothing, because the server treats a client-supplied action as hostile input.
 *
 * <h2>Several seats, one dealer</h2>
 *
 * <p>A table deals to {@code seats} hands that share one shoe and one dealer, the way a real one
 * does. Only the seats named in {@link #deal(boolean[])} take part in a hand; the rest sit it out
 * and keep a settlement of {@code null}. Seats act strictly in order — {@link #turn()} says whose
 * it is, and nobody else's action is legal meanwhile — and the dealer draws once at the end,
 * against every seat still standing.
 *
 * <p>The dealing order is the table order: one card to each playing seat left to right, the
 * dealer's up-card, a second card to each seat, then the hole card. With a single seat that is
 * exactly the old four-card sequence, so a pocket table deals the same cards from the same seed as
 * it did before seats existed.
 */
public final class BlackjackTable {

    /** How many seats a table block offers. The pocket device and the duel-free games use one. */
    public static final int MAX_SEATS = 3;

    private final Rules rules;
    private final Shoe shoe;

    private final Hand[] hands;
    private final int[] betUnits;
    private final boolean[] playing;
    private final boolean[] acted;
    /** A seat is done when it can take no further action: it stood, busted, doubled or surrendered. */
    private final boolean[] done;
    private final Settlement[] settlements;

    private final Hand dealer = new Hand();

    private BlackjackPhase phase = BlackjackPhase.WAITING;
    private int turn = -1;
    private boolean holeHidden = true;

    public BlackjackTable(Rules rules, Roller roller) {
        this(rules, roller, 1);
    }

    public BlackjackTable(Rules rules, Roller roller, int seats) {
        if (seats < 1 || seats > MAX_SEATS) throw new IllegalArgumentException("seats " + seats);
        this.rules = rules;
        this.shoe = new Shoe(rules.decks(), roller);
        this.hands = new Hand[seats];
        for (int i = 0; i < seats; i++) this.hands[i] = new Hand();
        this.betUnits = new int[seats];
        java.util.Arrays.fill(this.betUnits, 1);
        this.playing = new boolean[seats];
        this.acted = new boolean[seats];
        this.done = new boolean[seats];
        this.settlements = new Settlement[seats];
    }

    // ------------------------------------------------------------------ queries

    public Rules rules() { return rules; }

    public BlackjackPhase phase() { return phase; }

    public int seats() { return hands.length; }

    /** The seat that may act right now, or -1 when nobody may. */
    public int turn() { return turn; }

    public boolean isPlaying(int seat) { return inRange(seat) && playing[seat]; }

    public Hand hand(int seat) { return inRange(seat) ? hands[seat] : new Hand(); }

    public int betUnits(int seat) { return inRange(seat) ? betUnits[seat] : 1; }

    public Settlement settlement(int seat) { return inRange(seat) ? settlements[seat] : null; }

    /** Seat 0's hand. The solo games and the pocket table only ever have this one. */
    public Hand player() { return hands[0]; }

    public int betUnits() { return betUnits[0]; }

    public Settlement settlement() { return settlements[0]; }

    public Hand dealer() { return dealer; }

    /** True while the dealer's second card must not be sent to the client. */
    public boolean holeHidden() { return holeHidden; }

    /** The dealer's cards the client is allowed to know about right now. */
    public Hand dealerVisible() {
        if (!holeHidden) return dealer;
        Hand visible = new Hand();
        if (dealer.size() > 0) visible.add(dealer.get(0));
        return visible;
    }

    /**
     * Bitmask of the actions that are legal for this seat right now. The client uses it to grey out
     * buttons; the server re-derives it on every incoming action rather than trusting the client's
     * copy.
     */
    public int legalMask(int seat) {
        if (phase != BlackjackPhase.PLAYER_TURN || seat != turn || !inRange(seat)) return 0;
        Hand hand = hands[seat];
        int mask = BlackjackAction.HIT.bit() | BlackjackAction.STAND.bit();
        boolean firstDecision = hand.size() == 2 && !acted[seat];
        if (firstDecision && rules.allowDouble() && shoe.remaining() > 0) {
            mask |= BlackjackAction.DOUBLE.bit();
        }
        if (firstDecision && rules.allowSurrender()) {
            mask |= BlackjackAction.SURRENDER.bit();
        }
        return mask;
    }

    public int legalMask() { return legalMask(turn); }

    private boolean inRange(int seat) { return seat >= 0 && seat < hands.length; }

    // ------------------------------------------------------------------ transitions

    /** Deals a hand to seat 0 alone. */
    public boolean deal() {
        boolean[] only = new boolean[hands.length];
        only[0] = true;
        return deal(only);
    }

    /**
     * Deals the opening cards to every seat marked {@code true}. Returns false if called twice, or
     * with nobody playing.
     */
    public boolean deal(boolean[] seatsPlaying) {
        if (phase != BlackjackPhase.WAITING) return false;
        int count = 0;
        for (int i = 0; i < hands.length; i++) {
            playing[i] = i < seatsPlaying.length && seatsPlaying[i];
            if (playing[i]) count++;
        }
        if (count == 0) return false;

        for (int i = 0; i < hands.length; i++) if (playing[i]) hands[i].add(shoe.draw());
        dealer.add(shoe.draw());   // up-card
        for (int i = 0; i < hands.length; i++) if (playing[i]) hands[i].add(shoe.draw());
        dealer.add(shoe.draw());   // hole card

        // A peek settles the whole table at once: the dealer's natural beats every hand but another
        // natural, and no seat ever gets to act.
        boolean dealerCouldBeNatural = dealer.get(0).isAce() || dealer.get(0).isTenValue();
        if (rules.dealerPeek() && dealerCouldBeNatural && dealer.isNatural()) {
            holeHidden = false;
            for (int i = 0; i < hands.length; i++) {
                if (!playing[i]) continue;
                done[i] = true;
                settle(i, hands[i].isNatural() ? Outcome.PUSH : Outcome.DEALER_BLACKJACK);
            }
            phase = BlackjackPhase.SETTLED;
            turn = -1;
            return true;
        }

        // A seat dealt a natural is paid at once and sits out the turn order.
        for (int i = 0; i < hands.length; i++) {
            if (playing[i] && hands[i].isNatural()) {
                done[i] = true;
                settle(i, Outcome.PLAYER_BLACKJACK);
            }
        }

        phase = BlackjackPhase.PLAYER_TURN;
        advanceTurn();
        return true;
    }

    /** Applies an action for the seat whose turn it is. */
    public boolean apply(BlackjackAction action) { return apply(turn, action); }

    /** Applies a seat's action. Returns false — and changes nothing — if the action is illegal. */
    public boolean apply(int seat, BlackjackAction action) {
        if (action == null || !action.isIn(legalMask(seat))) return false;
        Hand hand = hands[seat];
        acted[seat] = true;
        switch (action) {
            case HIT -> {
                hand.add(shoe.draw());
                if (hand.isBust()) {
                    finishSeat(seat, Outcome.PLAYER_BUST);
                } else if (hand.total() == 21) {
                    standSeat(seat);         // nothing left to decide on 21
                }
            }
            case STAND -> standSeat(seat);
            case DOUBLE -> {
                betUnits[seat] = 2;
                hand.add(shoe.draw());
                if (hand.isBust()) finishSeat(seat, Outcome.PLAYER_BUST); else standSeat(seat);
            }
            case SURRENDER -> finishSeat(seat, Outcome.SURRENDER);
        }
        return true;
    }

    /** Forces the seat on turn to stand, used when its action deadline expires. */
    public boolean forceStand() { return forceStand(turn); }

    /** Forces one seat to stand — its deadline expired, or it left the table mid-hand. */
    public boolean forceStand(int seat) {
        if (phase != BlackjackPhase.PLAYER_TURN || seat != turn) return false;
        return apply(seat, BlackjackAction.STAND);
    }

    // ------------------------------------------------------------------ turn order

    /** The seat is done but still in the running: its result waits for the dealer. */
    private void standSeat(int seat) {
        done[seat] = true;
        advanceTurn();
    }

    /** The seat is done and its result is already known, so the dealer never plays for it. */
    private void finishSeat(int seat, Outcome outcome) {
        done[seat] = true;
        settle(seat, outcome);
        advanceTurn();
    }

    /**
     * Hands the turn to the next seat that still has a decision, or plays the dealer when none has.
     */
    private void advanceTurn() {
        for (int i = turn + 1; i < hands.length; i++) {
            if (playing[i] && !done[i]) { turn = i; return; }
        }
        turn = -1;
        playDealer();
    }

    // ------------------------------------------------------------------ dealer

    private void playDealer() {
        holeHidden = false;

        // Every seat busted, surrendered or had a natural: there is nothing left to beat, so the
        // dealer does not draw. Standing on a live hand is what makes the dealer play.
        boolean anyoneStanding = false;
        for (int i = 0; i < hands.length; i++) {
            if (playing[i] && settlements[i] == null) { anyoneStanding = true; break; }
        }
        if (!anyoneStanding) {
            phase = BlackjackPhase.SETTLED;
            return;
        }

        phase = BlackjackPhase.DEALER_TURN;
        while (true) {
            int total = dealer.total();
            if (total < 17) {
                dealer.add(shoe.draw());
                continue;
            }
            if (total == 17 && dealer.isSoft() && rules.dealerHitsSoft17()) {
                dealer.add(shoe.draw());
                continue;
            }
            break;
        }

        boolean dealerBust = dealer.isBust();
        int d = dealer.total();
        for (int i = 0; i < hands.length; i++) {
            if (!playing[i]) continue;
            if (settlements[i] != null) {
                // Settled before the dealer drew — a natural, a bust, a surrender. The result does
                // not change, but the dealer total it was stamped with is now stale, and that
                // number is on screen next to the seat.
                Settlement s = settlements[i];
                settlements[i] = new Settlement(s.outcome(), s.betUnits(), s.playerTotal(), d);
                continue;
            }
            if (dealerBust) {
                settle(i, Outcome.DEALER_BUST);
            } else {
                int p = hands[i].total();
                settle(i, p > d ? Outcome.WIN : (p == d ? Outcome.PUSH : Outcome.LOSS));
            }
        }
        phase = BlackjackPhase.SETTLED;
    }

    private void settle(int seat, Outcome outcome) {
        // A surrender only ever forfeits the original wager, never a doubled one.
        int units = outcome == Outcome.SURRENDER ? 1 : betUnits[seat];
        settlements[seat] = new Settlement(outcome, units, hands[seat].total(),
                dealer.size() > 0 ? dealer.total() : 0);
    }
}
