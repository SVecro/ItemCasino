import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.PayoutMath;
import com.itemcasino.core.game.SessionMachine;
import com.itemcasino.core.game.Roller;
import com.itemcasino.core.game.SeededRoller;
import com.itemcasino.core.game.blackjack.*;
import com.itemcasino.core.game.wheel.WheelMath;
import com.itemcasino.core.value.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Standalone verification harness for com.itemcasino.core.
 * No JUnit, no Minecraft: `javac` + `java` only. Mirrors src/test/java one-for-one.
 */
public final class CoreSelfTest {

    static int passed, failed;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        fixed();
        heap();
        graphSimple();
        graphMultiOutput();
        graphAlternatives();
        graphIngotBlockCycle();
        graphProfitableCycle();
        graphPinnedAndRemainder();
        graphScale();
        odds();
        estimates();
        seededRoller();
        wheel();
        payoutMath();
        sessionMachine();
        blackjackHands();
        blackjackMasks();
        blackjackDealerPolicy();
        blackjackNaturals();
        blackjackSoak();
        shoeUniformity();

        System.out.println();
        System.out.println("=".repeat(64));
        System.out.printf("  %d passed, %d failed%n", passed, failed);
        failures.forEach(f -> System.out.println("  FAIL " + f));
        System.out.println("=".repeat(64));
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    static void check(String what, boolean ok) {
        if (ok) { passed++; }
        else { failed++; failures.add(what); }
    }

    static void eq(String what, long expected, long actual) {
        boolean ok = expected == actual;
        if (!ok) failures.add(what + " (expected " + expected + ", got " + actual + ")");
        if (ok) passed++; else failed++;
    }

    static void section(String name) { System.out.println("-- " + name); }

    // ------------------------------------------------------------------ Fixed

    static void fixed() {
        section("Fixed");
        eq("ofPoints(9)", 9_000_000L, Fixed.ofPoints(9));
        eq("add saturates to INF", Fixed.INF, Fixed.add(Long.MAX_VALUE - 5, 10));
        eq("add absorbs INF", Fixed.INF, Fixed.add(Fixed.INF, 1));
        eq("sub floors at 0", 0L, Fixed.sub(5, 10));
        eq("mul saturates", Fixed.INF, Fixed.mul(Long.MAX_VALUE / 2, 3));
        eq("mul by zero", 0L, Fixed.mul(1000, 0));
        eq("divCeil rounds up", 3L, Fixed.divCeil(7, 3));
        eq("divCeil exact", 3L, Fixed.divCeil(9, 3));
        eq("divCeil floors at MIN_POSITIVE", Fixed.MIN_POSITIVE, Fixed.divCeil(0, 64));
        eq("divCeil INF", Fixed.INF, Fixed.divCeil(Fixed.INF, 4));
        eq("scale 5/2", 2_500_000L, Fixed.scale(Fixed.ofPoints(1), 5, 2));
        check("format", Fixed.format(1_234_500L).equals("1.23"));
        check("format unpriced", Fixed.format(Fixed.INF).equals("unpriced"));
    }

    static void heap() {
        section("LongHeap");
        LongHeap h = new LongHeap(4);
        long[] in = { 50, 3, 900, 12, 3, 7, 1_000_000, 0 };
        for (int i = 0; i < in.length; i++) h.push(in[i], i);
        long[] sorted = in.clone();
        Arrays.sort(sorted);
        boolean ok = true;
        for (long expected : sorted) {
            if (h.isEmpty() || h.peekKey() != expected) { ok = false; break; }
            h.pop();
        }
        check("heap pops ascending", ok && h.isEmpty());
    }

    // ------------------------------------------------------------------ value graph

    /** Convenience wrapper so the tests read like recipes. */
    static final class G {
        final ValueGraph.Builder b = new ValueGraph.Builder();
        ValueGraph graph;
        long[] seed;
        boolean[] pinned;
        ValueSolution sol;

        int item(String name) { return b.intern(name); }

        void recipe(String source, int out, int count, int... ins) {
            List<int[]> slots = new ArrayList<>();
            for (int in : ins) slots.add(new int[] { in });
            b.addConversion(source, out, count, slots, 0L, 0L);
        }

        void recipeOpt(String source, int out, int count, int[][] slots) {
            b.addConversion(source, out, count, List.of(slots), 0L, 0L);
        }

        void recipeExtra(String source, int out, int count, long surcharge, long remainder, int... ins) {
            List<int[]> slots = new ArrayList<>();
            for (int in : ins) slots.add(new int[] { in });
            b.addConversion(source, out, count, slots, surcharge, remainder);
        }

        void recipeReturning(String source, int out, int count, int[] returned, int... ins) {
            List<int[]> slots = new ArrayList<>();
            for (int in : ins) slots.add(new int[] { in });
            b.addConversion(source, out, count, slots, 0L, 0L, returned);
        }

        void solve(int[] seedItems, long[] seedValues, int[] pinnedItems) {
            graph = b.build();
            seed = new long[graph.itemCount];
            Arrays.fill(seed, Fixed.INF);
            pinned = new boolean[graph.itemCount];
            for (int i = 0; i < seedItems.length; i++) seed[seedItems[i]] = seedValues[i];
            if (pinnedItems != null) for (int p : pinnedItems) pinned[p] = true;
            sol = Relaxer.solve(graph, seed, pinned);
        }

        long v(int item) { return sol.value(item); }
    }

    static void graphSimple() {
        section("ValueGraph: linear chain");
        G g = new G();
        int raw = g.item("raw_iron");
        int ingot = g.item("iron_ingot");
        int pick = g.item("pickaxe");
        int stick = g.item("stick");
        g.recipe("smelt", ingot, 1, raw);
        g.recipe("pickaxe", pick, 1, ingot, ingot, ingot, stick, stick);
        g.solve(new int[] { raw, stick }, new long[] { Fixed.ofPoints(10), Fixed.ofPoints(1) }, null);

        eq("ingot from smelting", Fixed.ofPoints(10), g.v(ingot));
        eq("pickaxe = 3 ingots + 2 sticks", Fixed.ofPoints(32), g.v(pick));
        check("no cycles reported", g.sol.cycles().isEmpty());
        check("not capped", !g.sol.capped());
    }

    static void graphMultiOutput() {
        section("ValueGraph: multi-output division");
        G g = new G();
        int log = g.item("log");
        int planks = g.item("planks");
        int stick = g.item("stick");
        g.recipe("planks", planks, 4, log);
        g.recipe("sticks", stick, 4, planks, planks);
        g.solve(new int[] { log }, new long[] { Fixed.ofPoints(8) }, null);

        eq("planks = log / 4", Fixed.ofPoints(2), g.v(planks));
        eq("stick = 2 planks / 4", Fixed.ofPoints(1), g.v(stick));
    }

    static void graphAlternatives() {
        section("ValueGraph: tag alternatives take the cheapest");
        G g = new G();
        int oak = g.item("oak_log");
        int birch = g.item("birch_log");
        int planks = g.item("planks");
        g.recipeOpt("planks", planks, 4, new int[][] { { oak, birch } });
        g.solve(new int[] { oak, birch }, new long[] { Fixed.ofPoints(20), Fixed.ofPoints(8) }, null);
        eq("cheapest option wins", Fixed.ofPoints(2), g.v(planks));
    }

    static void graphIngotBlockCycle() {
        section("ValueGraph: ingot <-> block 2-cycle is stable");
        G g = new G();
        int ingot = g.item("iron_ingot");
        int block = g.item("iron_block");
        int[] nine = new int[9];
        Arrays.fill(nine, ingot);
        g.recipe("block", block, 1, nine);
        g.recipe("deconstruct", ingot, 9, block);
        g.solve(new int[] { ingot }, new long[] { Fixed.ofPoints(10) }, null);

        eq("block = 9 x ingot", Fixed.ofPoints(90), g.v(block));
        eq("ingot unchanged by the round trip", Fixed.ofPoints(10), g.v(ingot));
        check("terminated", !g.sol.capped());
    }

    static void graphProfitableCycle() {
        section("ValueGraph: value-creating cycle collapses to the floor, terminates, is reported");
        G g = new G();
        int a = g.item("A");
        int bb = g.item("B");
        g.recipe("a_to_2b", bb, 2, a);   // 1 A -> 2 B  => B = A/2
        g.recipe("b_to_a", a, 1, bb);    // 1 B -> 1 A  => A = B  => spirals down
        g.solve(new int[] { a }, new long[] { Fixed.ofPoints(1000) }, null);

        check("A hit the floor", g.v(a) == Fixed.MIN_POSITIVE);
        check("B hit the floor", g.v(bb) == Fixed.MIN_POSITIVE);
        check("terminated without the cap", !g.sol.capped());
        check("a derivation cycle was reported", !g.sol.cycles().isEmpty());
    }

    static void graphPinnedAndRemainder() {
        section("ValueGraph: pinned seeds and crafting remainders");
        G g = new G();
        int milk = g.item("milk_bucket");
        int bucket = g.item("bucket");
        int wheat = g.item("wheat");
        int cake = g.item("cake");
        int star = g.item("nether_star");
        int ingot = g.item("ingot");
        // cake eats 3 milk buckets but hands 3 empty buckets back
        g.recipeExtra("cake", cake, 1, 0L, Fixed.ofPoints(3 * 2), milk, milk, milk, wheat);
        // a "cheap" recipe that must not be allowed to lower the pinned nether star
        g.recipe("fake_star", star, 1, ingot);
        g.solve(new int[] { milk, bucket, wheat, star, ingot },
                new long[] { Fixed.ofPoints(10), Fixed.ofPoints(2), Fixed.ofPoints(1),
                             Fixed.ofPoints(20000), Fixed.ofPoints(5) },
                new int[] { star });

        eq("cake credits the returned buckets", Fixed.ofPoints(3 * 10 + 1 - 6), g.v(cake));

        // Same recipe, but the buckets are declared as remainder ITEMS so their value is looked up
        // dynamically -- which is what actually happens in game, where the bucket is itself derived.
        G h = new G();
        int hIngot = h.item("iron_ingot");
        int hBucket = h.item("bucket");
        int hMilk = h.item("milk_bucket");
        int hWheat = h.item("wheat");
        int hCake = h.item("cake");
        h.recipe("bucket", hBucket, 1, hIngot, hIngot, hIngot);
        h.recipe("milk", hMilk, 1, hBucket);
        h.recipeReturning("cake", hCake, 1, new int[] { hBucket, hBucket, hBucket },
                hMilk, hMilk, hMilk, hWheat);
        h.solve(new int[] { hIngot, hWheat },
                new long[] { Fixed.ofPoints(4), Fixed.ofPoints(1) }, null);
        eq("derived bucket costs 3 ingots", Fixed.ofPoints(12), h.v(hBucket));
        eq("milk bucket costs a bucket", Fixed.ofPoints(12), h.v(hMilk));
        eq("cake nets out the three returned buckets",
                Fixed.ofPoints(3 * 12 + 1 - 3 * 12), h.v(hCake));
        eq("pinned star is not lowered by a cheap recipe", Fixed.ofPoints(20000), g.v(star));
    }

    static void graphScale() {
        section("ValueGraph: 20k items / 60k recipes performance");
        int items = 20_000;
        ValueGraph.Builder b = new ValueGraph.Builder();
        for (int i = 0; i < items; i++) b.intern("item" + i);
        Random rnd = new Random(1234);
        for (int c = 0; c < 60_000; c++) {
            int out = rnd.nextInt(items);
            int slots = 1 + rnd.nextInt(4);
            List<int[]> in = new ArrayList<>(slots);
            for (int s = 0; s < slots; s++) in.add(new int[] { rnd.nextInt(items) });
            b.addConversion("r" + c, out, 1 + rnd.nextInt(4), in, 0L, 0L);
        }
        ValueGraph graph = b.build();
        long[] seed = new long[graph.itemCount];
        Arrays.fill(seed, Fixed.INF);
        for (int i = 0; i < 500; i++) seed[rnd.nextInt(items)] = Fixed.ofPoints(1 + rnd.nextInt(100));

        long t0 = System.nanoTime();
        ValueSolution sol = Relaxer.solve(graph, seed, new boolean[graph.itemCount]);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("   solved %d items / %d conversions in %d ms (%d relaxations, %d unpriced)%n",
                graph.itemCount, graph.conversionCount, ms, sol.relaxations(), sol.unpricedCount());
        check("large graph solves under 2000 ms", ms < 2000);
        check("large graph did not hit the cap", !sol.capped());
    }

    // ------------------------------------------------------------------ odds

    static void odds() {
        section("Odds");
        eq("equal values clamp to 90%", Odds.MAX_PPM, Odds.ppm(Fixed.ofPoints(100), Fixed.ofPoints(100)));
        eq("input above target clamps to 90%", Odds.MAX_PPM, Odds.ppm(Fixed.ofPoints(500), Fixed.ofPoints(100)));
        eq("half value -> 45%", 450_000L, Odds.ppm(Fixed.ofPoints(50), Fixed.ofPoints(100)));
        eq("tenth value -> 9%", 90_000L, Odds.ppm(Fixed.ofPoints(10), Fixed.ofPoints(100)));
        eq("1/900 is exactly the longest legal shot", Odds.MIN_PPM, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(900)));
        eq("tiny ratio is refused, not raised to 0.1%", Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(1_000_000)));
        eq("1 point against a dragon egg is refused", Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(50_000)));
        eq("unpriced target is illegal", Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(10), Fixed.INF));
        eq("unpriced input is illegal", Odds.ILLEGAL, Odds.ppm(Fixed.INF, Fixed.ofPoints(10)));
        eq("zero target is illegal", Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(10), 0));

        // monotonic in the input value, never outside the clamp
        boolean monotone = true, inRange = true;
        int prev = -1;
        for (long in = 1; in <= 2_000_000; in += 7919) {
            int p = Odds.ppm(in, 2_000_000L);
            if (p < prev) monotone = false;
            if (p != Odds.ILLEGAL && (p < Odds.MIN_PPM || p > Odds.MAX_PPM)) inRange = false;
            prev = p;
        }
        check("odds are monotone in the input value", monotone);
        check("legal odds always inside [0.1%, 90%]", inRange);

        // the house never pays more than 90% on average on any legal wager
        boolean fair = true;
        for (long target : new long[] {1, 256, 901, 20_000, 50_000, 1_000_000}) {
            for (long input = 1; input <= 2_000; input++) {
                int p = Odds.ppm(Fixed.ofPoints(input), Fixed.ofPoints(target));
                if (p == Odds.ILLEGAL) continue;
                if (p / 1_000_000.0 * target / input > 0.9 + 1e-9) fair = false;
            }
        }
        check("no legal upgrader wager returns more than 90% of its value", fair);

        // huge values must not overflow
        eq("huge but equal values still clamp", Odds.MAX_PPM,
                Odds.ppm(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4));
        int big = Odds.ppm(Long.MAX_VALUE / 8, Long.MAX_VALUE / 4);
        check("huge ratio 1/2 -> ~45%", Math.abs(big - 450_000) < 1000);
        check("percent formatting", Odds.percent(34_210).equals("3.42"));
    }

    // ------------------------------------------------------------------ estimates

    static void estimates() {
        section("EstimatedValues");
        ValueGraph.Builder b = new ValueGraph.Builder();
        int cobble = b.intern("cobble"), ice = b.intern("ice"), packed = b.intern("packed");
        int diamond = b.intern("diamond"), gem = b.intern("gem"), junk = b.intern("junk");
        List<int[]> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(new int[] { ice });
        b.addConversion("packed", packed, 1, nine, 0L, 0L);
        b.addConversion("gem_d", gem, 1, List.of(new int[][] { { diamond } }), 0L, 0L);
        b.addConversion("gem_j", gem, 1, List.of(new int[][] { { junk } }), 0L, 0L);
        ValueGraph g = b.build();
        long[] seed = new long[g.itemCount];
        Arrays.fill(seed, Fixed.INF);
        seed[cobble] = Fixed.ofPoints(1);
        seed[diamond] = Fixed.ofPoints(256);
        long[] guess = new long[g.itemCount];
        Arrays.fill(guess, Fixed.ofPoints(1));
        EstimatedValues.Result r = EstimatedValues.solve(g, seed, new boolean[g.itemCount], guess);
        check("a raw unpriced item is estimated", r.estimated()[ice]);
        eq("a crafted block of nine estimates is worth nine", Fixed.ofPoints(9), r.values()[packed]);
        check("a known item is not estimated", !r.estimated()[cobble]);
        eq("a known value is not undercut by a guessed path", Fixed.ofPoints(256), r.values()[gem]);
        check("and stays targetable", !r.estimated()[gem]);
    }

    static void seededRoller() {
        section("SeededRoller");
        boolean same = true;
        SeededRoller a = new SeededRoller(99L), c = new SeededRoller(99L);
        for (int i = 0; i < 10_000; i++) if (a.nextInt(416) != c.nextInt(416)) { same = false; break; }
        check("a seed replays the same stream", same);
        boolean handsMatch = true;
        for (long seed = 1; seed <= 500; seed++) {
            BlackjackTable live = new BlackjackTable(Rules.DEFAULT, new SeededRoller(seed));
            live.deal();
            int hits = 0;
            while (live.phase() == BlackjackPhase.PLAYER_TURN && live.player().total() < 15) { live.apply(BlackjackAction.HIT); hits++; }
            BlackjackTable again = new BlackjackTable(Rules.DEFAULT, new SeededRoller(seed));
            again.deal();
            for (int i = 0; i < hits; i++) again.apply(BlackjackAction.HIT);
            if (!live.dealer().cards().equals(again.dealer().cards())
                    || !live.player().cards().equals(again.player().cards())) { handsMatch = false; break; }
        }
        check("a blackjack hand rebuilt from seed + actions is identical, hole card included", handsMatch);
    }

    static void wheel() {
        section("WheelMath");
        Random rnd = new Random(7);
        boolean consistent = true;
        for (int trial = 0; trial < 200_000; trial++) {
            int ppm = Odds.MIN_PPM + rnd.nextInt(Odds.MAX_PPM - Odds.MIN_PPM);
            boolean win = rnd.nextBoolean();
            float angle = WheelMath.stopAngle(win, ppm, rnd.nextFloat());
            if (angle < 0 || angle >= WheelMath.TAU) { consistent = false; break; }
            if (WheelMath.isWinningAngle(angle, ppm) != win) { consistent = false; break; }
        }
        check("stop angle always lands in the arc matching the decided outcome", consistent);
        check("easeOutQuint(0)=0", WheelMath.easeOutQuint(0f) == 0f);
        check("easeOutQuint(1)=1", WheelMath.easeOutQuint(1f) == 1f);
        check("easeOutQuint monotone", WheelMath.easeOutQuint(0.3f) < WheelMath.easeOutQuint(0.6f));
        check("easeOutQuint decelerates", WheelMath.easeOutQuint(0.5f) > 0.9f);
    }

    static void payoutMath() {
        section("PayoutMath");
        long[] s = PayoutMath.split(3, 5, 2);          // 3 items, natural 3:2 -> 7.5
        eq("3 items at 5/2 -> 7 whole", 7L, s[0]);
        eq("residual numerator", 1L, s[1]);
        eq("residual denominator", 2L, s[2]);
        eq("DISCARD keeps 7", 7L, PayoutMath.applyRounding(s, PayoutMath.Rounding.DISCARD));
        eq("NEAREST rounds a half up to 8", 8L, PayoutMath.applyRounding(s, PayoutMath.Rounding.NEAREST));
        eq("UP rounds to 8", 8L, PayoutMath.applyRounding(s, PayoutMath.Rounding.UP));
        eq("CHANGE keeps 7 and pays the rest separately", 7L,
                PayoutMath.applyRounding(s, PayoutMath.Rounding.CHANGE));

        long[] even = PayoutMath.split(4, 5, 2);        // 10 exactly
        eq("4 items at 5/2 -> 10 whole", 10L, even[0]);
        eq("no residual", 0L, even[1]);
        eq("doubling", 8L, PayoutMath.split(4, 2, 1)[0]);
        eq("push returns the stake", 4L, PayoutMath.split(4, 1, 1)[0]);
        eq("loss returns nothing", 0L, PayoutMath.split(4, 0, 1)[0]);
        eq("surrender halves", 2L, PayoutMath.split(4, 1, 2)[0]);
    }

    // ------------------------------------------------------------------ session machine

    static void sessionMachine() {
        section("SessionMachine");

        // The whole point: a replayed settle cannot produce a second payout.
        check("SETTLING is a one-way door (nothing re-enters it from after)",
                !SessionMachine.isLegal(GameState.PAYOUT_PENDING, GameState.SETTLING)
                        && !SessionMachine.isLegal(GameState.IDLE, GameState.SETTLING));
        check("no state transitions to itself", noSelfLoops());
        check("ROLLING cannot go back to LOCKED (the outcome cannot be re-decided)",
                !SessionMachine.isLegal(GameState.ROLLING, GameState.LOCKED));
        check("ROLLING cannot skip straight to PAYOUT_PENDING",
                !SessionMachine.isLegal(GameState.ROLLING, GameState.PAYOUT_PENDING));
        check("the happy path is walkable", walk(GameState.IDLE, GameState.ARMED, GameState.LOCKED,
                GameState.ROLLING, GameState.SETTLING, GameState.PAYOUT_PENDING, GameState.IDLE));
        check("a loss skips the payout state", walk(GameState.ARMED, GameState.LOCKED,
                GameState.ROLLING, GameState.SETTLING, GameState.IDLE));
        check("every live state can abort", SessionMachine.isLegal(GameState.ARMED, GameState.ABORTED)
                && SessionMachine.isLegal(GameState.LOCKED, GameState.ABORTED)
                && SessionMachine.isLegal(GameState.ROLLING, GameState.ABORTED)
                && SessionMachine.isLegal(GameState.SETTLING, GameState.ABORTED));
        check("null is never legal", !SessionMachine.isLegal(null, GameState.IDLE)
                && !SessionMachine.isLegal(GameState.IDLE, null));

        // invariants: escrow must exist exactly while a wager is committed
        check("LOCKED without an escrow is a violation",
                !SessionMachine.invariantsHold(GameState.LOCKED, false, false, true));
        check("ROLLING with an escrow and an owner is fine",
                SessionMachine.invariantsHold(GameState.ROLLING, true, false, true));
        check("IDLE holding an escrow is a violation",
                !SessionMachine.invariantsHold(GameState.IDLE, true, false, false));
        check("ARMED holding an escrow is a violation",
                !SessionMachine.invariantsHold(GameState.ARMED, true, false, true));
        check("a payout outside PAYOUT_PENDING is a violation",
                !SessionMachine.invariantsHold(GameState.ARMED, false, true, true));
        check("PAYOUT_PENDING with nothing to pay is a violation",
                !SessionMachine.invariantsHold(GameState.PAYOUT_PENDING, false, false, true));
        check("PAYOUT_PENDING with a payout and an owner is fine",
                SessionMachine.invariantsHold(GameState.PAYOUT_PENDING, false, true, true));
        check("an ownerless live session is a violation",
                !SessionMachine.invariantsHold(GameState.ROLLING, true, false, false));
        check("IDLE, empty, ownerless is the resting state",
                SessionMachine.invariantsHold(GameState.IDLE, false, false, false));

        // every state/flag combination must be classified, never throw
        int combos = 0;
        boolean stable = true;
        for (GameState state : GameState.values()) {
            for (int mask = 0; mask < 8; mask++) {
                try {
                    SessionMachine.violation(state, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
                    combos++;
                } catch (RuntimeException e) {
                    stable = false;
                }
            }
        }
        check("all 56 state/flag combinations are classified without throwing", stable && combos == 56);

        // crash repair
        eq("a restored ROLLING with winnings parks them", GameState.PAYOUT_PENDING.ordinal(),
                SessionMachine.repairAfterLoad(GameState.ROLLING, true).ordinal());
        eq("a restored ROLLING with nothing owed goes idle", GameState.IDLE.ordinal(),
                SessionMachine.repairAfterLoad(GameState.ROLLING, false).ordinal());
        eq("a restored LOCKED is repaired too", GameState.IDLE.ordinal(),
                SessionMachine.repairAfterLoad(GameState.LOCKED, false).ordinal());
        eq("a restored PAYOUT_PENDING is left alone", GameState.PAYOUT_PENDING.ordinal(),
                SessionMachine.repairAfterLoad(GameState.PAYOUT_PENDING, true).ordinal());
        eq("a restored IDLE is left alone", GameState.IDLE.ordinal(),
                SessionMachine.repairAfterLoad(GameState.IDLE, false).ordinal());

        check("acceptsItems only in IDLE and ARMED",
                GameState.IDLE.acceptsItems() && GameState.ARMED.acceptsItems()
                        && !GameState.LOCKED.acceptsItems() && !GameState.ROLLING.acceptsItems()
                        && !GameState.SETTLING.acceptsItems()
                        && !GameState.PAYOUT_PENDING.acceptsItems());
        check("holdsEscrow only in LOCKED, ROLLING and SETTLING",
                GameState.LOCKED.holdsEscrow() && GameState.ROLLING.holdsEscrow()
                        && GameState.SETTLING.holdsEscrow() && !GameState.IDLE.holdsEscrow()
                        && !GameState.ARMED.holdsEscrow() && !GameState.PAYOUT_PENDING.holdsEscrow());
        check("byName falls back rather than throwing",
                GameState.byName("NONSENSE", GameState.IDLE) == GameState.IDLE
                        && GameState.byName("ROLLING", GameState.IDLE) == GameState.ROLLING);
    }

    static boolean noSelfLoops() {
        for (GameState s : GameState.values()) if (SessionMachine.isLegal(s, s)) return false;
        return true;
    }

    static boolean walk(GameState... path) {
        for (int i = 0; i + 1 < path.length; i++) {
            if (!SessionMachine.isLegal(path[i], path[i + 1])) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ blackjack

    static Card c(int rank, int suit) { return new Card(rank, suit); }

    static Hand hand(int... ranks) {
        Hand h = new Hand();
        for (int r : ranks) h.add(c(r, 0));
        return h;
    }

    static void blackjackHands() {
        section("Blackjack: hand totals");
        eq("A+K = 21", 21, hand(0, 12).total());
        check("A+K is natural", hand(0, 12).isNatural());
        check("A+K is soft", hand(0, 12).isSoft());
        eq("A+A = 12", 12, hand(0, 0).total());
        eq("A+A+9 = 21", 21, hand(0, 0, 8).total());
        eq("A+9+9 = 19 (ace demoted)", 19, hand(0, 8, 8).total());
        check("A+9+9 is hard", !hand(0, 8, 8).isSoft());
        eq("K+Q+2 = 22 bust", 22, hand(12, 11, 1).total());
        check("bust detected", hand(12, 11, 1).isBust());
        eq("7+7+7 = 21 but not natural", 21, hand(6, 6, 6).total());
        check("three sevens is not a natural", !hand(6, 6, 6).isNatural());
        eq("A+6 soft 17", 17, hand(0, 5).total());
        check("A+6 is soft", hand(0, 5).isSoft());
        eq("10+7 hard 17", 17, hand(9, 6).total());
        check("10+7 is hard", !hand(9, 6).isSoft());
        // card packing round trip over the whole deck
        boolean packOk = true;
        for (int r = 0; r < 13; r++)
            for (int s = 0; s < 4; s++)
                if (!Card.unpack(new Card(r, s).pack()).equals(new Card(r, s))) packOk = false;
        check("card packs into one byte and back", packOk);
    }

    static void blackjackMasks() {
        section("Blackjack: legal action mask");
        // Deterministic shoe: force a non-natural, non-peek opening.
        BlackjackTable t = tableWith(new int[] { 4, 5, 3, 6 });  // player 5+4=9, dealer 6+7=13
        t.deal();
        int mask = t.legalMask();
        check("HIT legal on the first decision", BlackjackAction.HIT.isIn(mask));
        check("STAND legal on the first decision", BlackjackAction.STAND.isIn(mask));
        check("DOUBLE legal on the first decision", BlackjackAction.DOUBLE.isIn(mask));
        check("SURRENDER legal on the first decision", BlackjackAction.SURRENDER.isIn(mask));

        t.apply(BlackjackAction.HIT);
        int after = t.legalMask();
        check("DOUBLE illegal after a hit", !BlackjackAction.DOUBLE.isIn(after));
        check("SURRENDER illegal after a hit", !BlackjackAction.SURRENDER.isIn(after));
        check("HIT still legal", BlackjackAction.HIT.isIn(after));

        check("illegal action is rejected and changes nothing",
                !t.apply(BlackjackAction.SURRENDER));
        check("null action is rejected", !t.apply(null));

        BlackjackTable settled = tableWith(new int[] { 0, 5, 12, 6 });  // player A+K natural
        settled.deal();
        eq("no legal actions once settled", 0, settled.legalMask());
        check("acting after settlement is rejected", !settled.apply(BlackjackAction.HIT));
    }

    static void blackjackDealerPolicy() {
        section("Blackjack: dealer soft-17 policy");
        // player 20 stands; dealer A+6 = soft 17, then a 3 would make 20.
        int[] order = { 9, 0, 11, 5, 2 };   // p:10 d:A p:Q d:6 next:3
        BlackjackTable s17 = tableWith(order, new Rules(1, false, true, true, false));
        s17.deal();
        s17.apply(BlackjackAction.STAND);
        eq("S17 dealer stands on soft 17", 17, s17.settlement().dealerTotal());
        check("S17: player 20 beats 17", s17.settlement().outcome() == Outcome.WIN);

        BlackjackTable h17 = tableWith(order, new Rules(1, true, true, true, false));
        h17.deal();
        h17.apply(BlackjackAction.STAND);
        eq("H17 dealer hits soft 17 and reaches 20", 20, h17.settlement().dealerTotal());
        check("H17: 20 vs 20 pushes", h17.settlement().outcome() == Outcome.PUSH);
    }

    static void blackjackNaturals() {
        section("Blackjack: naturals, doubles, surrender payouts");
        BlackjackTable nat = tableWith(new int[] { 0, 5, 12, 6 });   // player A+K, dealer 6+7
        nat.deal();
        check("player natural detected", nat.settlement().outcome() == Outcome.PLAYER_BLACKJACK);
        eq("natural pays 5", 5, nat.settlement().payNumerator());
        eq("over 2", 2, nat.settlement().payDenominator());
        check("natural multiplier is 2.5", nat.settlement().multiplier() == 2.5);
        check("hole card is revealed at settlement", !nat.holeHidden());

        BlackjackTable push = tableWith(new int[] { 0, 0, 12, 12 });  // both natural
        push.deal();
        check("both naturals push", push.settlement().outcome() == Outcome.PUSH);
        check("push returns the stake exactly", push.settlement().multiplier() == 1.0);

        BlackjackTable dealerNat = tableWith(new int[] { 8, 0, 8, 12 }); // p 9+9=18, d A+K
        dealerNat.deal();
        check("dealer natural on peek ends the hand immediately",
                dealerNat.settlement().outcome() == Outcome.DEALER_BLACKJACK);
        check("dealer natural pays nothing", dealerNat.settlement().multiplier() == 0.0);

        BlackjackTable dd = tableWith(new int[] { 4, 5, 5, 6, 9 });   // p 5+6=11, d 6+7, draw 10
        dd.deal();
        dd.apply(BlackjackAction.DOUBLE);
        eq("double sets two bet units", 2, dd.settlement().betUnits());
        eq("player doubled to 21", 21, dd.settlement().playerTotal());
        check("won double pays 4x", dd.settlement().multiplier() == 4.0);

        BlackjackTable sur = tableWith(new int[] { 4, 5, 3, 6 });
        sur.deal();
        sur.apply(BlackjackAction.SURRENDER);
        check("surrender outcome", sur.settlement().outcome() == Outcome.SURRENDER);
        check("surrender returns half", sur.settlement().multiplier() == 0.5);
        eq("surrender never forfeits a doubled stake", 1, sur.settlement().betUnits());

        BlackjackTable bust = tableWith(new int[] { 9, 5, 9, 6, 9 });  // p 20, hit 10 -> 30
        bust.deal();
        bust.apply(BlackjackAction.HIT);
        check("player bust", bust.settlement().outcome() == Outcome.PLAYER_BUST);
        check("bust pays nothing", bust.settlement().multiplier() == 0.0);
        check("hole revealed on bust", !bust.holeHidden());
    }

    /** Builds a table whose shoe deals the given ranks in order (suits irrelevant). */
    static BlackjackTable tableWith(int[] ranks) {
        return tableWith(ranks, new Rules(1, false, true, true, true));
    }

    static BlackjackTable tableWith(int[] ranks, Rules rules) {
        // The shoe shuffles with the Roller, so we invert: supply a Roller that produces the
        // permutation placing our desired ranks at the front. Simplest reliable approach is to
        // reject-sample a seed, which is fast enough for a handful of fixtures.
        for (long seed = 0; seed < 5_000_000L; seed++) {
            Random rnd = new Random(seed);
            BlackjackTable candidate = new BlackjackTable(rules, Roller.of(rnd));
            Shoe probe = new Shoe(rules.decks(), Roller.of(new Random(seed)));
            boolean match = true;
            for (int rank : ranks) {
                if (probe.draw().rank() != rank) { match = false; break; }
            }
            if (match) return candidate;
        }
        throw new IllegalStateException("no seed produced the fixture " + Arrays.toString(ranks));
    }

    static void blackjackSoak() {
        section("Blackjack: 300k-hand soak + house edge");
        Random rnd = new Random(20260913L);
        Rules rules = Rules.DEFAULT;
        long hands = 300_000;
        long wagered = 0, returned = 0;
        int naturals = 0, busts = 0, pushes = 0;
        boolean invariantsHold = true;

        for (long i = 0; i < hands; i++) {
            BlackjackTable t = new BlackjackTable(rules, Roller.of(rnd));
            t.deal();
            // "mimic the dealer" strategy: hit until 17 or more.
            int guard = 0;
            while (t.phase() == BlackjackPhase.PLAYER_TURN && guard++ < 20) {
                if (t.player().total() < 17) t.apply(BlackjackAction.HIT);
                else t.apply(BlackjackAction.STAND);
            }
            Settlement s = t.settlement();
            if (s == null || t.phase() != BlackjackPhase.SETTLED) { invariantsHold = false; break; }
            if (t.betUnits() < 1 || t.betUnits() > 2) { invariantsHold = false; break; }
            if (t.holeHidden()) { invariantsHold = false; break; }

            long unitsIn = 1000L * s.betUnits();
            long unitsOut = 1000L * s.payNumerator() / s.payDenominator();
            wagered += unitsIn;
            returned += unitsOut;
            if (s.outcome() == Outcome.PLAYER_BLACKJACK) naturals++;
            if (s.outcome() == Outcome.PLAYER_BUST) busts++;
            if (s.outcome() == Outcome.PUSH) pushes++;
        }

        double rtp = returned / (double) wagered;
        double naturalRate = naturals / (double) hands;
        System.out.printf("   RTP %.4f, naturals %.4f, bust rate %.4f, pushes %.4f%n",
                rtp, naturalRate, busts / (double) hands, pushes / (double) hands);

        check("no invariant violated across 300k hands", invariantsHold);
        check("mimic-the-dealer RTP is in the plausible 0.88-1.00 band", rtp > 0.88 && rtp < 1.00);
        check("natural rate is ~4.8%", Math.abs(naturalRate - 0.0483) < 0.004);
        check("bust rate is plausible (~16-32%)", busts / (double) hands > 0.16
                && busts / (double) hands < 0.32);
    }

    static void shoeUniformity() {
        section("Shoe: Fisher-Yates uniformity");
        Random rnd = new Random(99);
        int trials = 260_000;
        int[] firstRank = new int[13];
        for (int i = 0; i < trials; i++) {
            Shoe shoe = new Shoe(1, Roller.of(rnd));
            firstRank[shoe.draw().rank()]++;
        }
        double expected = trials / 13.0;
        double worst = 0;
        for (int count : firstRank) worst = Math.max(worst, Math.abs(count - expected) / expected);
        System.out.printf("   worst first-card rank deviation: %.3f%%%n", worst * 100);
        check("first drawn card is uniform over ranks (within 3%)", worst < 0.03);

        Shoe full = new Shoe(6, Roller.of(rnd));
        eq("6-deck shoe has 312 cards", 312, full.size());
        int[] seen = new int[13];
        while (full.remaining() > 0) seen[full.draw().rank()]++;
        boolean complete = true;
        for (int s : seen) if (s != 24) complete = false;
        check("a 6-deck shoe contains exactly 24 of every rank", complete);
    }
}
