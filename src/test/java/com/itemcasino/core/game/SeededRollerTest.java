package com.itemcasino.core.game;

import com.itemcasino.core.game.blackjack.BlackjackAction;
import com.itemcasino.core.game.blackjack.BlackjackPhase;
import com.itemcasino.core.game.blackjack.BlackjackTable;
import com.itemcasino.core.game.blackjack.Rules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeededRollerTest {

    @Test
    @DisplayName("the same seed deals the same stream, always")
    void deterministic() {
        SeededRoller a = new SeededRoller(123456789L);
        SeededRoller b = new SeededRoller(123456789L);
        for (int i = 0; i < 10_000; i++) assertEquals(a.nextInt(52), b.nextInt(52));
    }

    @Test
    @DisplayName("bounded ints are uniform")
    void uniform() {
        SeededRoller r = new SeededRoller(42L);
        int bound = 13;
        int[] counts = new int[bound];
        int n = 1_300_000;
        for (int i = 0; i < n; i++) counts[r.nextInt(bound)]++;
        for (int c : counts) assertTrue(Math.abs(c - n / bound) < n / bound * 0.02, "bucket " + c);
    }

    @Test
    @DisplayName("a hand rebuilt from its seed and actions is the hand that was dealt")
    void aHandSurvivesARestart() {
        for (long seed = 1; seed <= 2_000; seed++) {
            BlackjackTable live = new BlackjackTable(Rules.DEFAULT, new SeededRoller(seed));
            live.deal();
            java.util.List<BlackjackAction> taken = new java.util.ArrayList<>();
            while (live.phase() == BlackjackPhase.PLAYER_TURN && live.player().total() < 15) {
                live.apply(BlackjackAction.HIT);
                taken.add(BlackjackAction.HIT);
            }

            BlackjackTable rebuilt = new BlackjackTable(Rules.DEFAULT, new SeededRoller(seed));
            rebuilt.deal();
            for (BlackjackAction action : taken) rebuilt.apply(action);

            assertEquals(live.player().cards(), rebuilt.player().cards());
            assertEquals(live.dealer().cards(), rebuilt.dealer().cards(), "the hole card must survive too");
            assertEquals(live.phase(), rebuilt.phase());
        }
    }
}
