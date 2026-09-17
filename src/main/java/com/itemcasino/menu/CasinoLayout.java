package com.itemcasino.menu;

/**
 * One source of truth for where everything sits.
 *
 * <p>The menu decides slot positions on the server; the screen draws the panel on the client. When
 * those two carry their own copies of the numbers they drift, and a drifted copy looks exactly like
 * the bug it caused here: wells painted into the background texture that no real slot ever lined up
 * with, so players aimed at a hole and dropped items next to it. Nothing below is duplicated
 * anywhere else, and the background is painted from these same constants rather than baked into an
 * image that cannot be kept in step.
 *
 * <p>Everything is in GUI pixels, relative to the panel's top-left corner.
 */
public final class CasinoLayout {

    private CasinoLayout() {}

    /** Panel size. Wider than a chest because three of these screens carry a table, not a grid. */
    public static final int WIDTH = 216;
    public static final int HEIGHT = 228;
    public static final int CENTRE_X = WIDTH / 2;

    /** The felt: the whole play area, framed by the panel's border. */
    public static final int FELT_X = 7;
    public static final int FELT_Y = 18;
    public static final int FELT_W = WIDTH - 2 * FELT_X;
    public static final int FELT_H = 110;
    public static final int FELT_BOTTOM = FELT_Y + FELT_H;          // 128

    /** The action row, common to all three games: wager slot on the left, buttons filling the rest. */
    public static final int ROW_Y = 108;
    public static final int BUTTON_H = 18;
    public static final int WAGER_X = 20;
    public static final int WAGER_Y = ROW_Y;
    public static final int BUTTONS_X = 46;
    public static final int BUTTONS_RIGHT = WIDTH - 20;             // 196
    public static final int BUTTONS_W = BUTTONS_RIGHT - BUTTONS_X;  // 150

    /** The duel's second stake, mirrored across the action row from the first. */
    public static final int WAGER_B_X = WIDTH - 36;                 // 180
    public static final int DUEL_BUTTON_W = WAGER_B_X - 4 - BUTTONS_X;

    /** Player inventory, centred under the felt. */
    public static final int INV_X = (WIDTH - 9 * 18) / 2;           // 27
    public static final int INV_Y = 143;
    public static final int HOTBAR_Y = 203;
    public static final int INV_LABEL_Y = 132;

    /** Side gauges: two narrow columns flanking the centrepiece, used by every game for read-outs. */
    public static final int LEFT_GAUGE_X = 14;
    public static final int RIGHT_GAUGE_X = WIDTH - 14;
    public static final int GAUGE_W = 54;

    /** Where a dealt card or a spun wheel comes from, for the animations that need an origin. */
    public static final int SHOE_X = FELT_X + FELT_W - 28;   // 181
    public static final int SHOE_Y = FELT_Y + 4;             // 22

    public static int buttonX(int index, int count) {
        int gap = 2;
        int each = (BUTTONS_W - gap * (count - 1)) / count;
        return BUTTONS_X + index * (each + gap);
    }

    public static int buttonWidth(int count) {
        int gap = 2;
        return (BUTTONS_W - gap * (count - 1)) / count;
    }
}
