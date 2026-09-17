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
 */
public final class BlackjackTable {

    private final Rules rules;
    private final Shoe shoe;

    private final Hand player = new Hand();
    private final Hand dealer = new Hand();

    private BlackjackPhase phase = BlackjackPhase.WAITING;
    private int betUnits = 1;
    private boolean holeHidden = true;
    private boolean playerActed;
    private Settlement settlement;

    public BlackjackTable(Rules rules, Roller roller) {
        this.rules = rules;
        this.shoe = new Shoe(rules.decks(), roller);
    }

    // ------------------------------------------------------------------ queries

    public Rules rules() { return rules; }

    public BlackjackPhase phase() { return phase; }

    public Hand player() { return player; }

    public Hand dealer() { return dealer; }

    public int betUnits() { return betUnits; }

    /** True while the dealer's second card must not be sent to the client. */
    public boolean holeHidden() { return holeHidden; }

    public Settlement settlement() { return settlement; }

    /** The dealer's cards the client is allowed to know about right now. */
    public Hand dealerVisible() {
        if (!holeHidden) return dealer;
        Hand visible = new Hand();
        if (dealer.size() > 0) visible.add(dealer.get(0));
        return visible;
    }

    /**
     * Bitmask of the actions that are legal right now. The client uses it to grey out buttons;
     * the server re-derives it on every incoming action rather than trusting the client's copy.
     */
    public int legalMask() {
        if (phase != BlackjackPhase.PLAYER_TURN) return 0;
        int mask = BlackjackAction.HIT.bit() | BlackjackAction.STAND.bit();
        boolean firstDecision = player.size() == 2 && !playerActed;
        if (firstDecision && rules.allowDouble() && shoe.remaining() > 0) {
            mask |= BlackjackAction.DOUBLE.bit();
        }
        if (firstDecision && rules.allowSurrender()) {
            mask |= BlackjackAction.SURRENDER.bit();
        }
        return mask;
    }

    // ------------------------------------------------------------------ transitions

    /** Deals the opening four cards. Returns false if called twice. */
    public boolean deal() {
        if (phase != BlackjackPhase.WAITING) return false;
        player.add(shoe.draw());
        dealer.add(shoe.draw());   // up-card
        player.add(shoe.draw());
        dealer.add(shoe.draw());   // hole card

        boolean playerNatural = player.isNatural();
        boolean dealerCouldBeNatural = dealer.get(0).isAce() || dealer.get(0).isTenValue();
        boolean peeked = rules.dealerPeek() && dealerCouldBeNatural;
        boolean dealerNatural = dealer.isNatural();

        if (playerNatural || (peeked && dealerNatural)) {
            holeHidden = false;
            if (playerNatural && dealerNatural) {
                finish(Outcome.PUSH);
            } else if (playerNatural) {
                finish(Outcome.PLAYER_BLACKJACK);
            } else {
                finish(Outcome.DEALER_BLACKJACK);
            }
            return true;
        }

        phase = BlackjackPhase.PLAYER_TURN;
        return true;
    }

    /** Applies a player action. Returns false — and changes nothing — if the action is illegal. */
    public boolean apply(BlackjackAction action) {
        if (action == null || !action.isIn(legalMask())) return false;
        switch (action) {
            case HIT -> {
                playerActed = true;
                player.add(shoe.draw());
                if (player.isBust()) {
                    holeHidden = false;
                    finish(Outcome.PLAYER_BUST);
                } else if (player.total() == 21) {
                    playDealer();            // nothing left to decide on 21
                }
            }
            case STAND -> {
                playerActed = true;
                playDealer();
            }
            case DOUBLE -> {
                playerActed = true;
                betUnits = 2;
                player.add(shoe.draw());
                if (player.isBust()) {
                    holeHidden = false;
                    finish(Outcome.PLAYER_BUST);
                } else {
                    playDealer();
                }
            }
            case SURRENDER -> {
                playerActed = true;
                holeHidden = false;
                finish(Outcome.SURRENDER);
            }
        }
        return true;
    }

    /** Forces the hand to end, used when the player's action deadline expires. */
    public boolean forceStand() {
        if (phase != BlackjackPhase.PLAYER_TURN) return false;
        return apply(BlackjackAction.STAND);
    }

    // ------------------------------------------------------------------ dealer

    private void playDealer() {
        phase = BlackjackPhase.DEALER_TURN;
        holeHidden = false;

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

        if (dealer.isBust()) {
            finish(Outcome.DEALER_BUST);
            return;
        }
        int p = player.total();
        int d = dealer.total();
        finish(p > d ? Outcome.WIN : (p == d ? Outcome.PUSH : Outcome.LOSS));
    }

    private void finish(Outcome outcome) {
        phase = BlackjackPhase.SETTLED;
        holeHidden = false;
        // A surrender only ever forfeits the original wager, never a doubled one.
        int units = outcome == Outcome.SURRENDER ? 1 : betUnits;
        settlement = new Settlement(outcome, units, player.total(),
                dealer.size() > 0 ? dealer.total() : 0);
    }
}
