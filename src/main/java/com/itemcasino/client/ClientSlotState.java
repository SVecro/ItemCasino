package com.itemcasino.client;

import com.itemcasino.core.game.slots.SlotMachine;
import com.itemcasino.core.game.slots.SlotOutcome;
import com.itemcasino.core.game.slots.SlotSymbol;
import com.itemcasino.core.game.wheel.WheelMath;

/**
 * Where the three reels are, and where they are going to stop.
 *
 * <p>The faces arrive decided. All this class does is turn them into a scroll position per reel:
 * each one eases from nothing to a distance that happens to be a whole number of strip lengths plus
 * the index of its own face, so when the easing finishes the reel is showing exactly what the
 * server rolled. Reels stop left to right, a few ticks apart, because a machine where all three
 * stop together has no third act.
 *
 * <p>The visible strip is not the probability strip. A real machine's reel band shows each symbol a
 * handful of times and the odds live in the mechanism, which is the same split here: this strip is
 * only what scrolls past, while {@code SlotSymbol}'s weights decide what comes up.
 */
public final class ClientSlotState {

    /** The band painted on each reel. Every symbol appears at least once, or it could not land. */
    public static final SlotSymbol[] STRIP = {
            SlotSymbol.COAL, SlotSymbol.COPPER, SlotSymbol.COAL, SlotSymbol.IRON,
            SlotSymbol.COAL, SlotSymbol.GOLD, SlotSymbol.COPPER, SlotSymbol.COAL,
            SlotSymbol.IRON, SlotSymbol.DIAMOND, SlotSymbol.COAL, SlotSymbol.STAR,
    };

    /** Whole turns of the band before the first reel settles. More for each reel after it. */
    private static final int BASE_TURNS = 5;

    private static final int REELS = SlotMachine.REELS;

    private static final int[] faces = new int[REELS];
    private static final float[] travel = new float[REELS];
    private static final int[] duration = new int[REELS];

    private static boolean spinning;
    private static boolean landed;
    private static long sessionId;
    private static float elapsed;

    private ClientSlotState() {}

    /** A spin has been decided; work out how far each reel has to turn to show its face. */
    public static void begin(long sessionId, int left, int middle, int right,
                             int spinTicks, int stagger) {
        ClientSlotState.sessionId = sessionId;
        faces[0] = left;
        faces[1] = middle;
        faces[2] = right;
        for (int reel = 0; reel < REELS; reel++) {
            duration[reel] = Math.max(1, spinTicks + reel * stagger);
            travel[reel] = (BASE_TURNS + reel * 2) * STRIP.length + stripIndexOf(faces[reel]);
        }
        elapsed = 0F;
        spinning = true;
        landed = false;
    }

    /**
     * Shows a spin already in progress, for someone who just walked up to the machine.
     *
     * <p>They missed the packet, so they get the faces off the menu's data channel and a short
     * run-in rather than the full four seconds — the reels they are looking at are most of the way
     * round already.
     */
    public static void adopt(int packed) {
        if (spinning || packed < 0) return;
        begin(0L, packed & 0xF, (packed >> 4) & 0xF, (packed >> 8) & 0xF, 20, 8);
    }

    public static void tick() {
        if (!spinning) return;
        elapsed += 1F;
        if (elapsed >= duration[REELS - 1]) landed = true;
    }

    public static void reset() {
        spinning = false;
        landed = false;
        elapsed = 0F;
        sessionId = 0L;
    }

    public static boolean spinning() { return spinning; }

    /** True once every reel has settled: the cue to show what it paid. */
    public static boolean allStopped() { return !spinning || landed; }

    public static boolean stopped(int reel, float partialTick) {
        return !spinning || elapsed + partialTick >= duration[reel];
    }

    public static long sessionId() { return sessionId; }

    /**
     * How far this reel has scrolled, in symbols. The integer part picks the face on the payline
     * and the fraction is how far between two faces it currently sits.
     */
    public static float position(int reel, float partialTick) {
        if (!spinning) return stripIndexOf(faces[reel]);
        float t = WheelMath.clamp01((elapsed + partialTick) / duration[reel]);
        return WheelMath.easeOutQuint(t) * travel[reel];
    }

    /** What the machine decided, read off the same paytable the server used. */
    public static SlotOutcome outcome() {
        return SlotMachine.evaluate(faces[0], faces[1], faces[2]);
    }

    public static SlotSymbol symbolAt(int index) {
        int n = STRIP.length;
        return STRIP[((index % n) + n) % n];
    }

    private static int stripIndexOf(int symbolId) {
        SlotSymbol wanted = SlotSymbol.byId(symbolId);
        for (int i = 0; i < STRIP.length; i++) {
            if (STRIP[i] == wanted) return i;
        }
        return 0;
    }
}
