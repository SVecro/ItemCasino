package com.itemcasino.core.game.blackjack;

/** Player actions. The bit values form the legal-action mask sent to the client. */
public enum BlackjackAction {
    HIT(1),
    STAND(2),
    DOUBLE(4),
    SURRENDER(8);

    private final int bit;

    BlackjackAction(int bit) { this.bit = bit; }

    public int bit() { return bit; }

    public boolean isIn(int mask) { return (mask & bit) != 0; }

    public static BlackjackAction byId(int id) {
        BlackjackAction[] values = values();
        return (id < 0 || id >= values.length) ? null : values[id];
    }
}
