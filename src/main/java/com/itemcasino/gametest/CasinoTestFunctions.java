package com.itemcasino.gametest;

import com.itemcasino.ItemCasino;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * Registers the duplication suite.
 *
 * <p>Since the 1.21.5 game-test overhaul a test is a registered {@code Consumer<GameTestHelper>}
 * plus a {@code test_instance} datapack entry that binds it to a structure, rather than an
 * annotated method. Run them with {@code /test runall} on a world started with
 * {@code -Dneoforge.enabledGameTestNamespaces=itemcasino}, which the {@code gameTestServer} run
 * configuration already sets.
 */
public final class CasinoTestFunctions {

    public static final DeferredRegister<Consumer<GameTestHelper>> REGISTER =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, ItemCasino.MOD_ID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WAGER_LEAVES_THE_SLOT = REGISTER.register("wager_leaves_the_slot",
                    () -> CasinoGameTests::wagerLeavesTheSlot);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            REPLAYED_SETTLE_PAYS_ONCE = REGISTER.register("replayed_settle_pays_once",
                    () -> CasinoGameTests::replayedSettlePaysOnce);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SLOT_IS_SEALED = REGISTER.register("slot_is_sealed",
                    () -> CasinoGameTests::slotIsSealedDuringASession);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SPECTATORS_CANNOT_ACT = REGISTER.register("spectators_cannot_act",
                    () -> CasinoGameTests::spectatorsCannotAct);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            BREAKING_SPILLS_ONCE = REGISTER.register("breaking_spills_once",
                    () -> CasinoGameTests::breakingATableSpillsExactlyOnce);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            INTERRUPTED_HAND_IS_VOID = REGISTER.register("interrupted_hand_is_void",
                    () -> CasinoGameTests::interruptedBlackjackHandIsVoid);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            INVARIANTS_HOLD = REGISTER.register("invariants_hold",
                    () -> CasinoGameTests::invariantsHoldAcrossAFullCycle);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LOBBY_WAITS_FOR_EVERY_BET = REGISTER.register("lobby_waits_for_every_bet",
                    () -> CasinoGameTests::lobbyWaitsForEveryBet);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LOBBY_ALONE_DEALS_AT_ONCE = REGISTER.register("lobby_alone_deals_at_once",
                    () -> CasinoGameTests::lobbyAloneDealsAtOnce);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LOBBY_COUNTDOWN_DEALS_THE_READY = REGISTER.register("lobby_countdown_deals_the_ready",
                    () -> CasinoGameTests::lobbyCountdownDealsTheReady);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            THREE_SEATS_SURVIVE_A_RESTART = REGISTER.register("three_seats_survive_a_restart",
                    () -> CasinoGameTests::threeSeatsSurviveARestart);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LOBBY_RECHECKS_THE_STAKES = REGISTER.register("lobby_rechecks_the_stakes",
                    () -> CasinoGameTests::lobbyRechecksTheStakes);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SHARED_TABLE_DOUBLES_AND_CHIPS = REGISTER.register("shared_table_doubles_and_chips",
                    () -> CasinoGameTests::sharedTableDoublesAndChips);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            THREE_SEATS_SETTLE_APART = REGISTER.register("three_seats_settle_apart",
                    () -> CasinoGameTests::threeSeatsSettleApart);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WIN_PAYS_DOUBLE = REGISTER.register("win_pays_double",
                    () -> CasinoGameTests::winPaysDoubleOnAFullStack);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            PAYOUT_SURVIVES_FULL_INVENTORY = REGISTER.register("payout_survives_full_inventory",
                    () -> CasinoGameTests::payoutSurvivesAFullInventory);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DUEL_PAYS_ONLY_THE_WINNER = REGISTER.register("duel_pays_only_the_winner",
                    () -> CasinoGameTests::duelPaysOnlyTheWinner);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CONTAINERS_ARE_REFUSED = REGISTER.register("containers_are_refused",
                    () -> CasinoGameTests::containersAreRefused);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CLOSING_RETURNS_THE_STAKE = REGISTER.register("closing_returns_the_stake",
                    () -> CasinoGameTests::closingReturnsTheStake);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            UPGRADER_REFUSES_LONG_SHOTS = REGISTER.register("upgrader_refuses_long_shots",
                    () -> CasinoGameTests::upgraderRefusesLongShots);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DICE_ROLLS_MATCH_THEIR_ODDS = REGISTER.register("dice_rolls_match_their_odds",
                    () -> CasinoGameTests::diceRollsMatchTheirOdds);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            OLD_DOUBLE_OR_NOTHING_IS_REFUNDED = REGISTER.register("old_double_or_nothing_is_refunded",
                    () -> CasinoGameTests::oldDoubleOrNothingFlipIsRefunded);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            VAULT_SHARE_TAKES_ITS_PART = REGISTER.register("vault_share_takes_its_part",
                    () -> CasinoGameTests::vaultShareTakesOnlyItsPart);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MINE_FIELD_PAYS_WHAT_TILES_EARNED = REGISTER.register("mine_field_pays_what_tiles_earned",
                    () -> CasinoGameTests::mineFieldPaysWhatTheTilesEarned);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MINE_FIELD_RESTART_CASHES_OUT = REGISTER.register("mine_field_restart_cashes_out",
                    () -> CasinoGameTests::mineFieldRestartCashesOut);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CASHIER_EXCHANGES_AT_VALUE = REGISTER.register("cashier_exchanges_at_value",
                    () -> CasinoGameTests::cashierExchangesAtValue);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CHIP_DUEL_MOVES_CHIPS = REGISTER.register("chip_duel_moves_chips",
                    () -> CasinoGameTests::chipDuelMovesChips);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            VAULT_TAKES_CHIP_OFFERINGS = REGISTER.register("vault_takes_chip_offerings",
                    () -> CasinoGameTests::vaultTakesChipOfferings);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MINE_FIELD_STOPS_AT_THE_CEILING = REGISTER.register("mine_field_stops_at_the_ceiling",
                    () -> CasinoGameTests::mineFieldStopsAtTheCeiling);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LIQUIDATE_KEEPS_WHAT_A_PLAYER_CANNOT_HOLD = REGISTER.register("liquidate_keeps_what_a_player_cannot_hold",
                    () -> CasinoGameTests::liquidateKeepsWhatAPlayerCannotHold);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            RESTORED_DUEL_PAYS_ONCE = REGISTER.register("restored_duel_pays_once",
                    () -> CasinoGameTests::restoredDuelPaysOnce);


    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            FULL_POT_MAKES_ROOM = REGISTER.register("full_pot_makes_room",
                    () -> CasinoGameTests::fullPotMakesRoom);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SLOT_MACHINE_TAKES_ITS_CEILING = REGISTER.register("slot_machine_takes_its_ceiling",
                    () -> CasinoGameTests::slotMachineTakesItsCeiling);

    private CasinoTestFunctions() {}

}
