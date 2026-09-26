package com.itemcasino.gametest;

import com.itemcasino.CasinoConfig;
import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.SessionMachine;
import com.itemcasino.core.game.blackjack.BlackjackAction;
import com.itemcasino.core.game.blackjack.BlackjackPhase;
import com.itemcasino.registry.CasinoBlocks;
import com.itemcasino.menu.UpgraderMenu;
import com.itemcasino.player.CasinoMailbox;
import com.itemcasino.session.BlackjackSession;
import com.itemcasino.session.CoinFlipSession;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.DiceSession;
import com.itemcasino.session.MineFieldSession;
import com.itemcasino.session.VaultSession;
import com.itemcasino.core.game.MineField;
import com.itemcasino.core.game.Dice;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.chips.ChipCards;
import com.itemcasino.registry.CasinoItems;
import com.itemcasino.menu.CashierMenu;
import com.itemcasino.jackpot.Jackpot;
import com.itemcasino.session.UpgraderSession;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.core.game.blackjack.Outcome;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The duplication suite.
 *
 * <p>Every test here is a scenario from the attack-surface table: the ones that actually create or
 * destroy items. They drive the session directly rather than going through packets, because the
 * packet layer only decides <em>whether</em> to call these methods — the methods are where an item
 * can go missing or be created twice.
 *
 * <p>Failures are raised by throwing rather than through {@code helper.assertTrue}: the assertion
 * helpers' signatures have moved across the 1.21 line, while a thrown exception fails a game test
 * in every version.
 */
public final class CasinoGameTests {

    private static final BlockPos TABLE = new BlockPos(1, 1, 1);

    private CasinoGameTests() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("itemcasino gametest: " + message);
    }

    private static AbstractCasinoBlockEntity place(GameTestHelper helper, Block block) {
        helper.setBlock(TABLE, block.defaultBlockState());
        AbstractCasinoBlockEntity be = helper.getBlockEntity(TABLE, AbstractCasinoBlockEntity.class);
        check(be != null, "no casino block entity at " + TABLE);
        return be;
    }

    private static void noViolation(AbstractCasinoBlockEntity table, String where) {
        CasinoSession session = table.session();
        String violation = SessionMachine.violation(session.gameState(),
                session.holdsAnyEscrow(), !session.peekPayout().isEmpty(),
                table.seat() != null);
        check(violation == null, where + ": " + violation);
    }

    /**
     * A mock server player. {@code makeMockServerPlayerInLevel} is deprecated for removal and 1.21.11
     * offers no replacement that returns a {@code ServerPlayer} (only {@code makeMockPlayer}, a plain
     * {@code Player}); the suppression is confined to this one call.
     */
    @SuppressWarnings("removal")
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static ServerPlayer seat(GameTestHelper helper, AbstractCasinoBlockEntity table) {
        ServerPlayer player = mockPlayer(helper);
        // The mock player is hard-coded to creative, and a creative wager is refused by default --
        // an item conjured from nothing is not a wager. These tests are about escrow accounting
        // rather than about that guard, so the flag comes off before they start.
        player.getAbilities().instabuild = false;
        table.openFor(player);
        return player;
    }

    /**
     * Commits a wager, and says why if it will not commit.
     *
     * <p>{@code placeWager} answers a bare boolean because every refusal is already a message to
     * the player; a test that only reports "refused" sends you reading three classes to find out
     * which guard fired, so the state it was refused in is spelled out here.
     */
    private static void commit(CasinoSession session, ServerPlayer player, boolean accepted) {
        if (accepted) return;
        throw new IllegalStateException("itemcasino gametest: the wager was refused"
                + " (state=" + session.gameState()
                + ", creative=" + player.getAbilities().instabuild
                + ", wager=" + session.wagerStack()
                + ", valuesReady=" + com.itemcasino.valuation.ValuationEngine.snapshot().isReady() + ")");
    }

    private static int totalOf(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    // ------------------------------------------------------------------ tests

    /** The wager must leave the slot in the same tick it enters escrow, never exist in both. */
    public static void wagerLeavesTheSlot(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer player = seat(helper, table);
        UpgraderSession session = (UpgraderSession) table.session();

        session.wagerContainer().setItem(0, new ItemStack(Items.IRON_INGOT, 8));
        session.selectTarget(player, Identifier.withDefaultNamespace("diamond"));

        commit(session, player, session.placeWager(player));
        check(session.wagerStack().isEmpty(), "the wager is still in the slot after committing");
        check(session.escrowView().getCount() == 8, "the escrow does not hold the wagered stack");
        // The regression this exists for: a reentrant slot callback used to drive the session back
        // to IDLE here, stranding the escrow and making every wager look like a loss.
        check(session.gameState() == GameState.ROLLING,
                "expected ROLLING, got " + session.gameState());
        noViolation(table, "after committing");
        helper.succeed();
    }

    /** A replayed settle packet must not pay twice. */
    public static void replayedSettlePaysOnce(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer player = seat(helper, table);
        DiceSession session = (DiceSession) table.session();

        session.wagerContainer().setItem(0, ChipCards.newCard(40_000));
        session.setBet(player, 4);
        commit(session, player, session.placeWager(player));

        session.finishRoll(session.sessionId());
        long first = ChipCards.balance(session.wagerStack());
        for (int i = 0; i < 5; i++) session.finishRoll(session.sessionId());
        long afterReplays = ChipCards.balance(session.wagerStack());

        check(first == afterReplays,
                "five replayed settles changed the card from " + first + " to " + afterReplays);
        check(session.escrowView().isEmpty(), "the escrow survived settlement");
        check(first == 39_600 || first == 40_400, "4 chips at 48.5 % (x2) leave 396 or 404 chips, not " + first);
        check(session.peekPayout().isEmpty(), "a chip roll left something to claim: " + session.peekPayout());
        noViolation(table, "after settling");
        helper.succeed();
    }

    /** The slot must refuse both directions for the whole of a live session. */
    public static void slotIsSealedDuringASession(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer player = seat(helper, table);
        UpgraderSession session = (UpgraderSession) table.session();

        session.wagerContainer().setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
        session.selectTarget(player, Identifier.withDefaultNamespace("diamond"));
        commit(session, player, session.placeWager(player));

        check(!session.gameState().acceptsItems(),
                "the slot is still open during " + session.gameState());
        check(session.gameState().holdsEscrow() != session.escrowView().isEmpty(),
                "escrow presence disagrees with the state");
        helper.succeed();
    }

    /**
     * A spectator sees the table but may not touch it.
     *
     * <p>The onlooker is taken out of creative mode like the seated player. Before, it was left in
     * creative, so the commit was refused by the creative-mode guard and this test passed without
     * ever reaching the seat check it was written for.
     */
    public static void spectatorsCannotAct(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer seated = seat(helper, table);
        ServerPlayer onlooker = mockPlayer(helper);
        onlooker.getAbilities().instabuild = false;
        table.openFor(onlooker);

        CasinoSession session = table.session();
        check(session.isSeated(seated), "the first player through the door did not take the seat");
        check(!session.isSeated(onlooker), "a second player was given the seat as well");

        session.wagerContainer().setItem(0, ChipCards.newCard(20_000));
        check(session.gameState() == GameState.ARMED, "a chip card did not arm the table");
        check(!((DiceSession) session).placeWager(onlooker),
                "a spectator was allowed to commit someone else's wager");
        check(session.gameState() == GameState.ARMED && ChipCards.balance(session.wagerStack()) == 20_000,
                "a refused spectator commit still moved the stake");
        helper.succeed();
    }

    /**
     * Destroying a table hands back exactly what it held, once, to its owner — and a spin that was
     * already decided is paid as decided, not refunded.
     *
     * <p>Destroyed the way an explosion or another mod would do it, through the chunk, rather than
     * by calling the spill method directly: the old version of this test did the latter and so
     * never exercised any real removal path.
     */
    public static void breakingATableSpillsExactlyOnce(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer player = seat(helper, table);
        DiceSession session = (DiceSession) table.session();
        player.getInventory().clearContent();

        session.wagerContainer().setItem(0, ChipCards.newCard(60_000));
        session.setBet(player, 6);
        commit(session, player, session.placeWager(player));
        helper.destroyBlock(TABLE);

        BlockPos absolute = helper.absolutePos(TABLE);
        // The player is online and next to the table: the card comes back to them or drops with it.
        long chips = chipsIn(player) + chipsDropped(helper, absolute);
        int cards = countIn(player, CasinoItems.CHIP_CARD.get()) + countDropped(helper, absolute, CasinoItems.CHIP_CARD.get());
        check(cards == 1, "the card came back " + cards + " times");
        check(chips == 59_400 || chips == 60_600,
                "a decided 6-chip roll at x2 must leave 594 or 606 chips when the table is destroyed, left " + chips);
        check(session.escrowView().isEmpty(), "the escrow survived the table");
        check(session.peekPayout().isEmpty(), "the payout buffer survived the table");

        // And a stake that was never committed goes back to the seat's owner.
        player.getInventory().clearContent();
        AbstractCasinoBlockEntity second = place(helper, CasinoBlocks.UPGRADER.get());
        second.openFor(player);
        second.session().wagerContainer().setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        helper.destroyBlock(TABLE);
        int iron = countIn(player, Items.IRON_INGOT) + countDropped(helper, absolute, Items.IRON_INGOT)
                + (int) mailboxCount(helper, player, Items.IRON_INGOT);
        check(iron == 5, "an uncommitted stake of 5 came back as " + iron);
        helper.succeed();
    }

    /**
     * A hand interrupted by a restart is rebuilt from its seed and stood — never refunded.
     *
     * <p>Refunding it was a free option: quit on every bad hand, keep every good one. The table is
     * saved and loaded for real here, the way a chunk reload does it.
     */
    public static void interruptedBlackjackHandIsVoid(GameTestHelper helper) {
        // The table and its player are made once, outside the loop, and every attempt leaves the
        // table idle before the next one starts.
        //
        // This used to place and seat inside the loop, which looked independent and was not:
        // place() sets the same block at the same position, so the block entity — and its session —
        // survives, and a natural was left mid-reveal for the next attempt to trip over. Roughly
        // one run in eleven deals a natural on the first hand, so the test failed about that often,
        // for a reason that had nothing to do with what it was testing.
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        ServerPlayer player = seat(helper, table);
        BlackjackSession session = (BlackjackSession) table.session();

        for (int attempt = 0; attempt < 40; attempt++) {
            player.getInventory().clearContent();
            session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 3));
            commit(session, player, session.placeWager(player));
            if (session.gameState() != GameState.ROLLING || session.table() == null
                    || session.table().phase() != BlackjackPhase.PLAYER_TURN) {
                // A natural settled at once. Finish it properly and deal another, so the next
                // attempt starts from an idle table rather than a hand still being revealed.
                revealHand(session);
                session.takePayout();
                session.wagerContainer().setItem(0, ItemStack.EMPTY);
                check(session.gameState() == GameState.IDLE,
                        "a settled natural left the table in " + session.gameState());
                continue;
            }
            List<Card> playerCards = List.copyOf(session.table().player().cards());
            Card dealerUp = session.table().dealer().get(0);
            Card dealerHole = session.table().dealer().get(1);

            CompoundTag saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
            BlockEntity loaded = BlockEntity.loadStatic(table.getBlockPos(), table.getBlockState(),
                    saved, helper.getLevel().registryAccess());
            check(loaded instanceof AbstractCasinoBlockEntity, "the table did not load back");
            BlackjackSession restored = (BlackjackSession) ((AbstractCasinoBlockEntity) loaded).session();

            check(!restored.gameState().holdsEscrow(), "a restored hand is still holding the stake");
            check(restored.escrowView().isEmpty(), "a restored hand kept its escrow");
            check(restored.restoredHand() != null, "the hand was not rebuilt from its seed");
            List<Card> rebuilt = restored.restoredHand().player().cards();
            check(rebuilt.size() >= 2 && rebuilt.subList(0, playerCards.size()).equals(playerCards),
                    "the rebuilt hand is not the hand that was dealt");
            check(restored.restoredHand().dealer().get(0).equals(dealerUp)
                    && restored.restoredHand().dealer().get(1).equals(dealerHole),
                    "the dealer's cards changed across the restart");

            Outcome outcome = restored.restoredHand().settlement().outcome();
            int paid = totalOf(restored.peekPayout());
            int expected = switch (outcome) {
                case WIN, DEALER_BUST -> 6;
                case PUSH -> 3;
                default -> 0;
            };
            check(paid == expected, "a stood hand ending " + outcome + " paid " + paid);
            helper.succeed();
            return;
        }
        throw new IllegalStateException("itemcasino gametest: forty naturals in a row");
    }

    /**
     * The winner of a duel is the player in the winning chair, whether or not they have the screen
     * open — the regression for the loser collecting the pot. Mock players never have a casino menu
     * open, which is exactly the situation that used to hand the pot to whoever clicked first.
     */
    public static void duelPaysOnlyTheWinner(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.COIN_FLIP.get());
        ServerPlayer a = seat(helper, table);
        ServerPlayer b = seat(helper, table);
        CoinFlipSession session = (CoinFlipSession) table.session();
        check(session.seatIndex(a) == 0 && session.seatIndex(b) == 1, "the duellists did not get one chair each");

        a.getInventory().clearContent();
        b.getInventory().clearContent();
        session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 4));
        session.wagerContainerB().setItem(0, new ItemStack(Items.DIAMOND, 4));
        check(session.placeWager(a), "the first duellist could not ready");
        check(session.placeWager(b), "the second duellist could not ready");
        check(session.gameState() == GameState.ROLLING, "the coin did not go up: " + session.gameState());
        session.finishFlip(session.sessionId());

        UUID winner = session.payoutOwner();
        check(winner != null, "the duel has no named winner");
        ServerPlayer won = winner.equals(a.getUUID()) ? a : b;
        ServerPlayer lost = won == a ? b : a;
        check(!session.canClaim(lost), "the loser may collect the pot");
        check(session.canClaim(won), "the winner may not collect the pot");

        // The pot goes straight to the winner at the settle; nothing is left to be claimed by anyone.
        check(session.peekPayout().isEmpty(), "the pot is still waiting to be collected");
        check(countIn(lost, Items.DIAMOND) == 0, "the loser received diamonds");
        long wonDiamonds = countIn(won, Items.DIAMOND) + mailboxCount(helper, won, Items.DIAMOND);
        check(wonDiamonds == 8, "the winner received " + wonDiamonds + " of 8");
        helper.succeed();
    }

    /** Shulker boxes and bundles never go in a casino slot, and are never valued as a wager. */
    public static void containersAreRefused(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer player = seat(helper, table);
        UpgraderMenu menu = new UpgraderMenu(0, player.getInventory(), table.session(), player);

        check(menu.slots.get(0).mayPlace(new ItemStack(Items.DIAMOND)), "the slot refuses a diamond");
        check(!menu.slots.get(0).mayPlace(new ItemStack(Items.SHULKER_BOX)), "the slot accepts a shulker box");
        check(!menu.slots.get(0).mayPlace(new ItemStack(Items.RED_SHULKER_BOX)), "the slot accepts a dyed shulker box");
        check(!menu.slots.get(0).mayPlace(new ItemStack(Items.BUNDLE)), "the slot accepts a bundle");
        check(menu.slots.get(0).mayPlace(new ItemStack(Items.CHEST)),
                "an empty chest item was refused as if it were a shulker box");
        check(StackValuator.reject(new ItemStack(Items.SHULKER_BOX), ValuationEngine.snapshot())
                == StackValuator.Rejection.CONTAINER, "a shulker box is still a legal wager");
        helper.succeed();
    }

    /** Closing the screen with nothing committed gives the stake back and frees the chair. */
    public static void closingReturnsTheStake(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer first = seat(helper, table);
        first.getInventory().clearContent();
        table.session().wagerContainer().setItem(0, new ItemStack(Items.IRON_INGOT, 5));

        table.onViewerClosed(first);
        check(table.seat() == null, "the chair is still taken after closing with nothing committed");
        check(table.session().wagerStack().isEmpty(), "the stake was left in the slot");
        check(countIn(first, Items.IRON_INGOT) == 5, "the stake did not come back to its owner");

        ServerPlayer second = seat(helper, table);
        check(table.session().isSeated(second), "the next player could not sit down");
        check(table.session().wagerStack().isEmpty(), "the next player found someone else's stake");
        helper.succeed();
    }

    /** A shot longer than one in a thousand is refused, not raised to one in a thousand. */
    public static void upgraderRefusesLongShots(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer player = seat(helper, table);
        UpgraderSession session = (UpgraderSession) table.session();

        session.wagerContainer().setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        session.selectTarget(player, Identifier.withDefaultNamespace("dragon_egg"));
        check(session.gameState() != GameState.ARMED, "one cobblestone against a dragon egg armed the wheel");
        check(!session.placeWager(player), "one cobblestone against a dragon egg was accepted");
        check(session.wagerStack().getCount() == 1, "a refused long shot still took the stake");
        helper.succeed();
    }

    /** Every state the machine reaches must satisfy its invariants. */
    public static void invariantsHoldAcrossAFullCycle(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.UPGRADER.get());
        ServerPlayer player = seat(helper, table);
        UpgraderSession session = (UpgraderSession) table.session();
        noViolation(table, "idle");

        session.wagerContainer().setItem(0, new ItemStack(Items.IRON_INGOT, 16));
        session.selectTarget(player, Identifier.withDefaultNamespace("diamond"));
        noViolation(table, "armed");

        commit(session, player, session.placeWager(player));
        noViolation(table, "rolling");

        session.finishSpin(session.sessionId());
        noViolation(table, "settled");

        session.deliverPayout(player);
        noViolation(table, "after collecting");
        helper.succeed();
    }

    /**
     * A full stack that wins must come back doubled — as two stacks, not one.
     *
     * <p>This is the regression for the bug that started it: sixty-four in, a win, and sixty-four
     * out. The payout list itself was right; what lost half of it was believing vanilla's
     * {@code Inventory#add}, which answers "did I place ANY of this" and mutates the stack down to
     * whatever would not fit. A partial add therefore looked like a complete one and the remainder
     * was dropped on the floor of a method rather than the floor of the world.
     */
    /**
     * The lobby with three players looking at the table.
     *
     * <p>Two bet, the third only watches. The first Ready starts the countdown and does not deal;
     * pressing it again takes the word back and stops the countdown. With both bettors ready the
     * cards still wait, because the third player is looking and has not said ready. When that
     * player walks away, everyone still looking is ready, and the next tick deals: to the two chairs
     * that said so, never to the empty one, and on the short clock of a shared hand.
     */
    public static void lobbyWaitsForEveryBet(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        ServerPlayer[] players = new ServerPlayer[3];
        for (int index = 0; index < 3; index++) {
            players[index] = seat(helper, table);
            players[index].getInventory().clearContent();
            lookAt(helper, table, players[index]);
        }
        check(session.presentMask() == 0b111,
                "three players looking read as " + Integer.toBinaryString(session.presentMask()));

        session.seatContainer(0).setItem(0, new ItemStack(Items.DIAMOND, 8));
        session.seatContainer(1).setItem(0, new ItemStack(Items.DIAMOND, 8));
        check(session.gameState() == GameState.ARMED, "two bets did not arm the table");

        check(session.commitWager(players[0]), "the first chair could not say it was ready");
        check(session.isReady(0), "saying ready did not take");
        check(session.gameState() == GameState.ARMED,
                "the hand started while the others were still deciding");
        int seconds = session.readout(BlackjackSession.READOUT_COUNTDOWN);
        check(seconds > 0 && seconds <= CasinoConfig.SERVER.lobbySeconds.get(),
                "the first Ready did not start the countdown (it reads " + seconds + ")");

        // Pressing again takes the word back, and with nobody ready the countdown stops.
        check(session.commitWager(players[0]), "the first chair could not take its word back");
        check(!session.isReady(0), "pressing ready twice did not take it back");
        check(session.readout(BlackjackSession.READOUT_COUNTDOWN) == 0,
                "the countdown kept running with nobody ready");
        check(session.gameState() == GameState.ARMED, "taking a word back started the hand");

        // Both bettors ready -- but the third player is looking and has not said so.
        check(session.commitWager(players[0]), "the first chair could not say it was ready again");
        check(session.commitWager(players[1]), "the second chair could not say it was ready");
        check(session.gameState() == GameState.ARMED,
                "the hand started while a player at the table had not said ready");

        // The third walks away: nobody left to wait for.
        lookAway(players[2]);
        helper.runAfterDelay(2, () -> {
            check(session.gameState() == GameState.ROLLING,
                    "the hand did not start once everyone looking was ready");
            check(session.table() != null, "the hand started without a table");
            check(session.table().isPlaying(0) && session.table().isPlaying(1),
                    "a chair that was ready was not dealt in");
            check(!session.table().isPlaying(2), "the empty chair was dealt in");
            check(!session.isReady(0) && !session.isReady(1),
                    "the ready flags survived into the hand");
            check(session.decisionSeconds() == CasinoConfig.SERVER.sharedActionSeconds.get(),
                    "a hand for two ran on a " + session.decisionSeconds() + "-second clock");
            noViolation(table, "with a hand dealt from the lobby");
            helper.succeed();
        });
    }

    /**
     * A player alone at a three-chair table is dealt at once: there is nobody to wait for, so the
     * button deals exactly as it does at one chair, with no countdown, and the hand runs on the
     * longer clock of a hand nobody else is waiting on.
     */
    public static void lobbyAloneDealsAtOnce(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        check(session.seats() == 3, "a blackjack table seated " + session.seats() + " and not three");
        ServerPlayer player = seat(helper, table);
        player.getInventory().clearContent();
        lookAt(helper, table, player);
        check(session.presentMask() == 0b001,
                "one player looking read as " + Integer.toBinaryString(session.presentMask()));

        session.seatContainer(0).setItem(0, new ItemStack(Items.DIAMOND, 8));
        check(session.commitWager(player), "a player alone at the table could not deal");
        check(session.gameState() == GameState.ROLLING, "a player alone at the table was made to wait");
        check(session.readout(BlackjackSession.READOUT_COUNTDOWN) == 0,
                "a countdown started with nobody to wait for");
        check(session.table() != null && session.table().isPlaying(0), "the player was not dealt in");
        check(!session.table().isPlaying(1) && !session.table().isPlaying(2),
                "an empty chair was dealt in");
        check(session.decisionSeconds() == CasinoConfig.SERVER.playerActionSeconds.get(),
                "a hand played alone ran on a " + session.decisionSeconds() + "-second clock");
        noViolation(table, "with a hand dealt to a player alone");

        // Play it out, so the test leaves a table holding nothing.
        int guard = 0;
        while (session.table() != null
                && session.table().phase() == BlackjackPhase.PLAYER_TURN && guard++ < 8) {
            check(session.act(player, session.sessionId(), BlackjackAction.STAND),
                    "the player could not stand");
        }
        revealHand(session);
        check(session.gameState() != GameState.ROLLING, "the hand never settled");
        helper.succeed();
    }

    /**
     * Two players looking, one ready: when the countdown runs out, the cards go to the one who
     * said so. The other sits the hand out and keeps every item of its bet in its box.
     */
    public static void lobbyCountdownDealsTheReady(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        ServerPlayer[] players = new ServerPlayer[2];
        for (int index = 0; index < 2; index++) {
            players[index] = seat(helper, table);
            players[index].getInventory().clearContent();
            lookAt(helper, table, players[index]);
        }
        session.seatContainer(0).setItem(0, new ItemStack(Items.DIAMOND, 8));
        session.seatContainer(1).setItem(0, new ItemStack(Items.DIAMOND, 8));

        check(session.commitWager(players[0]), "the first chair could not say it was ready");
        check(session.gameState() == GameState.ARMED,
                "the hand started with the second player still deciding");
        check(session.readout(BlackjackSession.READOUT_COUNTDOWN) > 0, "no countdown started");

        long wait = CasinoConfig.SERVER.lobbySeconds.get() * 20L + 5L;
        helper.runAfterDelay(wait, () -> {
            check(session.gameState() == GameState.ROLLING, "the countdown ran out and nobody was dealt");
            check(session.table() != null && session.table().isPlaying(0),
                    "the chair that said ready was not dealt in");
            check(!session.table().isPlaying(1), "a chair that never said ready was dealt in");
            ItemStack kept = session.seatContainer(1).getItem(0);
            check(kept.is(Items.DIAMOND) && kept.getCount() == 8,
                    "the chair that sat out kept " + kept + " of its 8 diamonds");
            check(session.seatContainer(0).getItem(0).isEmpty(), "the dealt chair's stake never left its box");
            noViolation(table, "with a hand dealt by the countdown");
            helper.succeed();
        });
    }

    /**
     * A shared hand interrupted by a save and a load pays every chair, not just the first.
     *
     * <p>The regression for the audit of 2026-09-24, §2.1. The repair runs while the table is being
     * loaded, before the chunk gives it a level; it used to hand chairs two and three their winnings
     * right there, with no server to reach a mailbox and no world to drop anything in, and the
     * items simply vanished. Now they wait in each chair's own saved buffer and go out on the first
     * tick. The copy is loaded exactly as a chunk loads it -- no level -- and only then given one.
     */
    public static void threeSeatsSurviveARestart(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        ServerPlayer[] players = new ServerPlayer[3];
        for (int index = 0; index < 3; index++) players[index] = seat(helper, table);

        for (int attempt = 0; attempt < 40; attempt++) {
            for (int index = 0; index < 3; index++) {
                players[index].getInventory().clearContent();
                session.seatContainer(index).setItem(0, new ItemStack(Items.DIAMOND, 8));
            }
            commit(session, players[2], session.placeWager(players[2]));
            if (session.gameState() == GameState.ROLLING && session.table() != null
                    && session.table().phase() == BlackjackPhase.PLAYER_TURN) {
                CompoundTag saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
                BlockEntity loaded = BlockEntity.loadStatic(table.getBlockPos(), table.getBlockState(),
                        saved, helper.getLevel().registryAccess());
                check(loaded instanceof AbstractCasinoBlockEntity, "the table did not load back");
                AbstractCasinoBlockEntity copy = (AbstractCasinoBlockEntity) loaded;
                BlackjackSession restored = (BlackjackSession) copy.session();
                check(restored.restoredHand() != null, "the shared hand was not rebuilt from its seed");

                int[] owed = new int[3];
                boolean neighbourOwed = false;
                for (int index = 0; index < 3; index++) {
                    com.itemcasino.core.game.blackjack.Settlement settled =
                            restored.restoredHand().settlement(index);
                    check(settled != null, "chair " + index + " was restored with no settlement");
                    owed[index] = (int) (8L * settled.payNumerator() / settled.payDenominator());
                    if (index > 0 && owed[index] > 0) neighbourOwed = true;
                }
                if (neighbourOwed) {
                    // Given a world only now, as the chunk does; the first tick hands over.
                    copy.setLevel(helper.getLevel());
                    restored.tick();
                    for (int index = 1; index < 3; index++) {
                        long got = countIn(players[index], Items.DIAMOND)
                                + mailboxCount(helper, players[index], Items.DIAMOND);
                        check(got == owed[index], "chair " + index + " was owed " + owed[index]
                                + " after the restart and received " + got);
                    }
                    check(totalOf(restored.peekPayout()) == owed[0],
                            "chair 0 was owed " + owed[0] + " and holds " + totalOf(restored.peekPayout()));
                    // A second tick hands nothing over twice.
                    restored.tick();
                    for (int index = 1; index < 3; index++) {
                        long got = countIn(players[index], Items.DIAMOND)
                                + mailboxCount(helper, players[index], Items.DIAMOND);
                        check(got == owed[index], "chair " + index + " was paid twice: " + got);
                    }
                    helper.succeed();
                    return;
                }
            }
            // A natural settled at once, or nobody but chair 0 was owed anything: play it out and
            // deal again, from an idle table.
            int guard = 0;
            while (session.table() != null && session.table().phase() == BlackjackPhase.PLAYER_TURN
                    && guard++ < 16) {
                int turn = session.table().turn();
                check(session.act(players[turn], session.sessionId(), BlackjackAction.STAND),
                        "chair " + turn + " could not stand");
            }
            revealHand(session);
            session.takePayout();
            for (int index = 0; index < 3; index++) session.seatContainer(index).setItem(0, ItemStack.EMPTY);
        }
        throw new IllegalStateException("itemcasino gametest: forty shared hands owed no neighbour anything");
    }

    /**
     * A shared table deals only stakes it has just checked.
     *
     * <p>Two players looking. Changing a box after Ready withdraws it. And a stake that stops being
     * acceptable without the box being touched -- a card drained under one chip -- is caught when
     * the cards come out: that chair sits the hand out, keeps its card, and the other chair plays.
     */
    public static void lobbyRechecksTheStakes(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        ServerPlayer[] players = new ServerPlayer[2];
        for (int index = 0; index < 2; index++) {
            players[index] = seat(helper, table);
            players[index].getInventory().clearContent();
            lookAt(helper, table, players[index]);
        }
        session.seatContainer(0).setItem(0, ChipCards.newCard(1_000));   // ten chips
        session.setBet(players[0], 5);
        session.seatContainer(1).setItem(0, new ItemStack(Items.DIAMOND, 8));

        // A changed box withdraws Ready.
        check(session.commitWager(players[1]), "the diamond chair could not say it was ready");
        check(session.isReady(1), "Ready did not take");
        check(session.gameState() == GameState.ARMED, "one Ready of two dealt the hand");
        session.seatContainer(1).setItem(0, new ItemStack(Items.DIAMOND, 9));
        check(!session.isReady(1), "a box changed after Ready was still ready");

        // Chair 0 says ready on ten chips, then its card is drained without the box being touched.
        check(session.commitWager(players[0]), "the card chair could not say it was ready");
        check(session.isReady(0), "the card chair's Ready did not take");
        ChipCards.setBalance(session.seatContainer(0).getItem(0), 50);   // half a chip
        check(session.commitWager(players[1]), "the diamond chair could not say it was ready again");

        check(session.gameState() == GameState.ROLLING, "the valid chair was not dealt");
        check(session.table() != null && session.table().isPlaying(1), "the diamond chair was not dealt in");
        check(!session.table().isPlaying(0), "a card under one chip was dealt in");
        check(!session.isReady(0), "the refused chair was left ready");
        ItemStack card = session.seatContainer(0).getItem(0);
        check(ChipCards.isCard(card) && ChipCards.balance(card) == 50,
                "the refused chair's card did not stay in its box untouched: " + card);
        noViolation(table, "with one chair refused at the deal");
        helper.succeed();
    }

    /**
     * Doubling and chips at a shared table. Chair 0 bets chips, chair 1 bets diamonds and doubles
     * with a second stack from its own inventory, chair 2 bets diamonds and stands. Each is paid
     * exactly its own settlement: the collateral is taken from the doubling player alone, the card
     * comes back to its own box with the right balance, and the table is left holding nothing.
     */
    public static void sharedTableDoublesAndChips(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        ServerPlayer[] players = new ServerPlayer[3];
        for (int index = 0; index < 3; index++) players[index] = seat(helper, table);

        for (int attempt = 0; attempt < 40; attempt++) {
            for (int index = 0; index < 3; index++) players[index].getInventory().clearContent();
            session.seatContainer(0).setItem(0, ChipCards.newCard(10_000));   // a hundred chips
            session.setBet(players[0], 10);
            session.seatContainer(1).setItem(0, new ItemStack(Items.DIAMOND, 8));
            session.seatContainer(2).setItem(0, new ItemStack(Items.DIAMOND, 8));
            players[1].getInventory().add(new ItemStack(Items.DIAMOND, 8));   // the collateral

            commit(session, players[2], session.placeWager(players[2]));
            boolean doubled = false;
            int guard = 0;
            while (session.table() != null && session.table().phase() == BlackjackPhase.PLAYER_TURN
                    && guard++ < 16) {
                int turn = session.table().turn();
                if (turn == 1 && !doubled
                        && BlackjackAction.DOUBLE.isIn(session.legalMaskFor(players[1]))) {
                    check(session.act(players[1], session.sessionId(), BlackjackAction.DOUBLE),
                            "chair 1 could not double");
                    doubled = true;
                    check(countIn(players[1], Items.DIAMOND) == 0,
                            "doubling did not take the second stack from the doubling player");
                    check(countIn(players[0], Items.DIAMOND) == 0 && countIn(players[2], Items.DIAMOND) == 0,
                            "doubling took something from a neighbour");
                } else {
                    check(session.act(players[turn], session.sessionId(), BlackjackAction.STAND),
                            "chair " + turn + " could not stand");
                }
            }
            com.itemcasino.core.game.blackjack.BlackjackTable finished = session.table();
            check(finished != null, "the hand vanished before it could be settled");
            if (!doubled) {
                revealHand(session);
                session.takePayout();
                for (int index = 0; index < 3; index++) session.seatContainer(index).setItem(0, ItemStack.EMPTY);
                continue;
            }

            com.itemcasino.core.game.blackjack.Settlement chipChair = finished.settlement(0);
            com.itemcasino.core.game.blackjack.Settlement doubler = finished.settlement(1);
            com.itemcasino.core.game.blackjack.Settlement stander = finished.settlement(2);
            check(chipChair != null && doubler != null && stander != null, "a chair reached the end unsettled");
            check(doubler.betUnits() == 2 || doubler.outcome() == Outcome.SURRENDER,
                    "the double was not recorded on chair 1");
            long chipsPaid = Chips.payout(1_000, chipChair.payNumerator(), chipChair.payDenominator());
            long expectedBalance = 10_000 - 1_000 + chipsPaid;
            int owedDoubler = (int) (8L * doubler.payNumerator() / doubler.payDenominator());
            int owedStander = (int) (8L * stander.payNumerator() / stander.payDenominator());

            revealHand(session);
            check(session.gameState() != GameState.ROLLING, "the hand never settled");

            ItemStack card = session.seatContainer(0).getItem(0);
            check(ChipCards.isCard(card) && ChipCards.balance(card) == expectedBalance,
                    "the chip chair's card came back with " + ChipCards.balance(card)
                            + " cents, not " + expectedBalance + " (" + chipChair.outcome() + ")");
            long doublerGot = countIn(players[1], Items.DIAMOND) + mailboxCount(helper, players[1], Items.DIAMOND);
            check(doublerGot == owedDoubler, "the doubler was owed " + owedDoubler + " (" + doubler.outcome()
                    + ") and received " + doublerGot);
            long standerGot = countIn(players[2], Items.DIAMOND) + mailboxCount(helper, players[2], Items.DIAMOND);
            check(standerGot == owedStander, "the stander was owed " + owedStander + " (" + stander.outcome()
                    + ") and received " + standerGot);
            check(!session.hasLiveWager(), "the table still holds a wager after the hand");
            noViolation(table, "after a shared hand with a double and chips");
            helper.succeed();
            return;
        }
        throw new IllegalStateException("itemcasino gametest: chair 1 never got to double in forty hands");
    }

    /**
     * Gives a mock player the table's screen, the way opening it for real would: a live menu on
     * this session, and standing at the table so the menu stays valid.
     */
    private static void lookAt(GameTestHelper helper, AbstractCasinoBlockEntity table, ServerPlayer player) {
        BlockPos at = helper.absolutePos(TABLE);
        player.snapTo(at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 1.5D);
        player.containerMenu = table.createMenu(100 + table.session().seatIndex(player),
                player.getInventory(), player);
    }

    /** Closes that screen through the menu's own teardown, as the close packet would. */
    private static void lookAway(ServerPlayer player) {
        player.containerMenu.removed(player);
        player.containerMenu = player.inventoryMenu;
    }

    /**
     * Three players at one table, one hand, three separate settlements.
     *
     * <p>This is the test the whole seat refactor exists for. It checks the things that would be
     * invisible until somebody lost money: that each chair's stake leaves its own box and none of
     * the others', that only the chair on turn may act, that the hand pays each chair against the
     * same dealer, and — the one that matters most — that the table is left holding nothing
     * afterwards. Every stake in, every payout out, nothing stranded and nothing conjured.
     */
    public static void threeSeatsSettleApart(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        BlackjackSession session = (BlackjackSession) table.session();
        check(session.seats() == 3, "a blackjack table seated " + session.seats() + " and not three");

        ServerPlayer[] players = new ServerPlayer[3];
        for (int index = 0; index < 3; index++) {
            players[index] = seat(helper, table);
            players[index].getInventory().clearContent();
            check(session.seatIndex(players[index]) == index,
                    "the " + index + "th player through the door took seat "
                            + session.seatIndex(players[index]));
        }

        // One diamond each, in each chair's own box.
        for (int index = 0; index < 3; index++) {
            net.minecraft.world.SimpleContainer box = session.seatContainer(index);
            check(box != null, "seat " + index + " had no box to bet into");
            box.setItem(0, new ItemStack(Items.DIAMOND, 8));
        }
        check(session.gameState() == GameState.ARMED, "three stakes did not arm the table");

        // The LAST chair deals, on purpose. The button names whoever pressed it, and a version of
        // this that dealt from seat 0 could not tell the difference between "seat 0 was paid" and
        // "the player who dealt was paid" -- which is exactly the bug that shipped: the first
        // chair's winnings followed the presser, so a player who dealt for the table collected a
        // neighbour's refund on top of their own loss.
        commit(session, players[2], session.placeWager(players[2]));
        check(session.gameState() == GameState.ROLLING, "the hand did not start");
        for (int index = 0; index < 3; index++) {
            check(session.seatContainer(index).getItem(0).isEmpty(),
                    "seat " + index + "'s stake never left its box");
        }

        // Only the chair on turn may act, and the others are refused without changing anything.
        int guard = 0;
        while (session.table() != null
                && session.table().phase() == BlackjackPhase.PLAYER_TURN && guard++ < 24) {
            int turn = session.table().turn();
            check(turn >= 0 && turn < 3, "the table was waiting on nobody while still in play");
            for (int index = 0; index < 3; index++) {
                if (index == turn) continue;
                check(!session.act(players[index], session.sessionId(), BlackjackAction.STAND),
                        "seat " + index + " acted on seat " + turn + "'s turn");
            }
            check(session.act(players[turn], session.sessionId(), BlackjackAction.STAND),
                    "the chair on turn could not stand");
        }
        // What the table decided, taken before settling throws the hand away. Asking each chair's
        // own settlement what it is owed is the only honest check: a bound on the total was the
        // first attempt and it was simply wrong -- it forgot that a natural pays 5/2, so three
        // winning chairs one of which was dealt 21 legitimately hand back 52 for 24 staked.
        int[] owed = new int[3];
        String[] why = new String[3];
        com.itemcasino.core.game.blackjack.BlackjackTable finished = session.table();
        check(finished != null, "the hand vanished before it could be settled");
        for (int index = 0; index < 3; index++) {
            com.itemcasino.core.game.blackjack.Settlement settled = finished.settlement(index);
            check(settled != null, "seat " + index + " reached the end with no settlement");
            owed[index] = (int) (8L * settled.payNumerator() / settled.payDenominator());
            why[index] = settled.outcome().name();
        }

        revealHand(session);

        // Each chair scored against the one dealer, and each was paid exactly its own result.
        check(session.gameState() != GameState.ROLLING, "the hand never settled");
        for (int index = 0; index < 3; index++) {
            long mine = countIn(players[index], Items.DIAMOND)
                    + mailboxCount(helper, players[index], Items.DIAMOND)
                    + session.seatContainer(index).getItem(0).getCount();
            check(mine == owed[index], "seat " + index + " ended " + why[index]
                    + ", which owes " + owed[index] + " diamonds on an 8-diamond bet, and was paid "
                    + mine);
        }

        // Nothing stranded: no escrow, no buffer, no stake left in a box nobody owns.
        check(!session.hasLiveWager(), "the table was still holding a live wager after settling");
        check(session.peekPayout().isEmpty(), "seat 0's payout was left to be collected");
        noViolation(table, "after a three-handed hand settled");
        helper.succeed();
    }

    public static void winPaysDoubleOnAFullStack(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        ServerPlayer player = seat(helper, table);
        BlackjackSession session = (BlackjackSession) table.session();

        // Blackjack decides the outcome from a shuffled shoe, so the hand is played out until an
        // ordinary win turns up rather than forced. Forty attempts against a ~42% win rate is a
        // margin of well over a billion to one.
        for (int attempt = 0; attempt < 40; attempt++) {
            player.getInventory().clearContent();
            long mailedBefore = mailboxCount(helper, player, Items.DIAMOND);
            session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 64));
            commit(session, player, session.placeWager(player));

            if (session.gameState() == GameState.ROLLING
                    && session.table() != null
                    && session.table().phase() == BlackjackPhase.PLAYER_TURN) {
                session.act(player, session.sessionId(), BlackjackAction.STAND);
            }
            revealHand(session);

            // Paid at the settle, straight into the inventory: nothing waits in the table.
            check(session.peekPayout().isEmpty(), "a settled hand left its payout to be collected");
            long paid = countIn(player, Items.DIAMOND) + mailboxCount(helper, player, Items.DIAMOND) - mailedBefore;
            if (paid == 128) {
                check(countIn(player, Items.DIAMOND) == 128, "the win was 128 diamonds but only "
                        + countIn(player, Items.DIAMOND) + " reached an empty inventory");
                noViolation(table, "after a doubled stack was paid");
                helper.succeed();
                return;
            }
            check(paid == 0 || paid == 32 || paid == 64 || paid == 160,
                    "a 64-diamond hand paid " + paid);
        }
        throw new IllegalStateException("itemcasino gametest: forty hands without a single win");
    }

    /**
     * A payout bigger than the room available must end up on the floor, never nowhere.
     *
     * <p>The same partial-add trap, from the other side: with one slot free and two stacks owed,
     * the first add succeeds partially and the rest has to survive somewhere.
     */
    public static void payoutSurvivesAFullInventory(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
        ServerPlayer player = seat(helper, table);
        BlackjackSession session = (BlackjackSession) table.session();

        // Leave exactly one empty slot and fill the rest with something that cannot merge.
        player.getInventory().clearContent();
        for (int slot = 1; slot < player.getInventory().getContainerSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.BEDROCK, 1));
        }
        long mailedBefore = mailboxCount(helper, player, Items.DIAMOND);

        session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 64));
        commit(session, player, session.placeWager(player));
        if (session.gameState() == GameState.ROLLING && session.table() != null
                && session.table().phase() == BlackjackPhase.PLAYER_TURN) {
            session.act(player, session.sessionId(), BlackjackAction.STAND);
        }
        revealHand(session);

        int held = countIn(player, Items.DIAMOND);
        long mailed = mailboxCount(helper, player, Items.DIAMOND) - mailedBefore;
        int parked = totalOf(session.peekPayout());
        long total = held + mailed + parked;
        check(total == 0 || total == 32 || total == 64 || total == 128 || total == 160,
                "a 64-diamond hand paid " + total + " (" + held + " held, " + mailed + " mailed, "
                        + parked + " parked): some of it vanished");
        check(held <= 64, "one free slot took " + held + " diamonds");
        check(total <= 64 || mailed > 0, "a payout bigger than the free space was not kept in the mailbox");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the dice

    /**
     * Rolls pay exactly what their line says, as often as their chance says, and never more than the
     * payout ceiling allows.
     *
     * <p>Two thousand rolls at 25 % over (x3.88): wins are expected near 500 with a standard deviation
     * of about 19, and the bounds sit more than four and a half deviations out.
     */
    public static void diceRollsMatchTheirOdds(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer player = seat(helper, table);
        DiceSession session = (DiceSession) table.session();

        session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 4));
        check(!session.placeWager(player), "the dice table took items");
        check(session.wagerStack().getCount() == 4, "a refused item stake left the slot");
        session.wagerContainer().setItem(0, ChipCards.newCard(100_000_000L));

        session.setOption(player, Dice.pack(5, false));
        check(session.chance() == com.itemcasino.CasinoConfig.SERVER.diceMinChance.get(),
                "a 0.05 % bet was not raised to the minimum: " + session.chance());
        session.setOption(player, Dice.pack(2500, true));
        check(session.chance() == 2500 && session.over(), "the bet did not become 25 % over");
        session.setBet(player, 4);

        long multiplier = Dice.multiplierPpm(2500, com.itemcasino.CasinoConfig.SERVER.diceEdgePpm.get());
        long winPays = Chips.payout(400, multiplier);
        int wins = 0;
        for (int i = 0; i < 2000; i++) {
            long before = ChipCards.balance(session.wagerStack());
            commit(session, player, session.placeWager(player));
            int roll = session.lastRoll();
            session.finishRoll(session.sessionId());
            long after = ChipCards.balance(session.wagerStack());
            if (roll >= 7500) {
                wins++;
                check(after == before - 400 + winPays, "roll " + roll + " over 75.00 moved the card from "
                        + before + " to " + after + ", not by " + (winPays - 400));
            } else {
                check(after == before - 400, "roll " + roll + " over 75.00 moved the card from " + before + " to " + after);
            }
            check(session.peekPayout().isEmpty(), "roll " + i + " left something to claim");
            check(session.gameState() == GameState.ARMED, "roll " + i + " ended in " + session.gameState());
        }
        check(wins >= 410 && wins <= 590, "2000 rolls at 25 % won " + wins + " times, expected about 500");

        // The ceiling: at 1 % (x97) the bet is capped so that the win stays under the limit.
        session.setOption(player, Dice.pack(100, false));
        session.setBet(player, 1_000_000);
        long cap = Dice.maxStake(com.itemcasino.CasinoConfig.SERVER.diceMaxPayoutChips.get(),
                Dice.multiplierPpm(100, com.itemcasino.CasinoConfig.SERVER.diceEdgePpm.get()));
        check(session.effectiveBetChips() == cap, "a 1 % bet was allowed " + session.effectiveBetChips()
                + " chips, not the " + cap + " the ceiling allows");
        noViolation(table, "after the ceiling checks");
        helper.succeed();
    }

    /**
     * A Double or Nothing flip saved mid-air by the old table loads as a dice table with no roll to
     * replay, and is refunded in full.
     */
    public static void oldDoubleOrNothingFlipIsRefunded(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer player = seat(helper, table);
        DiceSession session = (DiceSession) table.session();

        session.wagerContainer().setItem(0, ChipCards.newCard(10_000));
        session.setBet(player, 1);
        commit(session, player, session.placeWager(player));
        check(session.gameState() == GameState.ROLLING, "the roll did not start: " + session.gameState());

        CompoundTag saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
        // What the old table wrote: its id, seven diamonds in escrow, and none of the dice or chip fields.
        for (String key : List.of("dice_roll", "dice_chance", "dice_over", "dice_bet_chance",
                "dice_bet_over", "dice_bet_multiplier_ppm", "stake_cents", "bet_chips", "chip_payout_cents")) {
            saved.remove(key);
        }
        saved.store("escrow", ItemStack.OPTIONAL_CODEC,
                helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                new ItemStack(Items.DIAMOND, 7));
        saved.putString("id", "itemcasino:double_or_nothing");
        BlockEntity loaded = BlockEntity.loadStatic(table.getBlockPos(), table.getBlockState(),
                saved, helper.getLevel().registryAccess());
        check(loaded instanceof AbstractCasinoBlockEntity, "an old Double or Nothing table did not load back");
        CasinoSession restored = ((AbstractCasinoBlockEntity) loaded).session();
        check(restored instanceof DiceSession, "the old table did not come back as a dice table");
        check(!restored.gameState().holdsEscrow(), "the old flip is still holding the stake");
        check(totalOf(restored.peekPayout()) == 7, "the old flip refunded " + totalOf(restored.peekPayout()) + " of 7");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the Vault's share

    /**
     * A share of the pot hands over exactly that share of each kind, rounded down, and leaves the
     * rest; and a smaller share is quoted better odds for the same offering.
     */
    public static void vaultShareTakesOnlyItsPart(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.VAULT.get());
        ServerPlayer player = seat(helper, table);
        VaultSession session = (VaultSession) table.session();
        Jackpot jackpot = Jackpot.of(helper.getLevel());

        Jackpot.bank(helper.getLevel(), new ItemStack(Items.EMERALD_BLOCK), 203);
        long before = jackpot.countOf(Items.EMERALD_BLOCK);

        session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 2));
        session.setOption(player, 100);
        int whole = session.readout(VaultSession.READOUT_DRAW_PPM);
        session.setOption(player, 10);
        check(session.sharePercent() == 10, "the share did not change: " + session.sharePercent());
        int tenth = session.readout(VaultSession.READOUT_DRAW_PPM);
        check(whole > 0 && tenth > whole, "a tenth of the pot was quoted " + tenth
                + " ppm against " + whole + " ppm for all of it");
        session.setOption(player, 0);
        check(session.sharePercent() == 1, "a share of 0 was not clamped to 1");
        session.setOption(player, 250);
        check(session.sharePercent() == 100, "a share of 250 was not clamped to 100");
        session.wagerContainer().setItem(0, ItemStack.EMPTY);

        player.getInventory().clearContent();
        long mailedBefore = mailboxCount(helper, player, Items.EMERALD_BLOCK);
        long value = jackpot.award(player, 25);
        long received = countIn(player, Items.EMERALD_BLOCK)
                + mailboxCount(helper, player, Items.EMERALD_BLOCK) - mailedBefore;
        check(value > 0, "a quarter of the pot was worth nothing");
        check(received == before * 25 / 100, "a quarter of " + before + " emerald blocks paid " + received);
        check(jackpot.countOf(Items.EMERALD_BLOCK) == before - received,
                "the pot kept " + jackpot.countOf(Items.EMERALD_BLOCK) + " of " + before
                        + " after paying " + received);
        helper.succeed();
    }

    // ------------------------------------------------------------------ the mine field

    private static int firstTile(MineFieldSession session, boolean mine) {
        for (int t = 0; t < MineField.TILES; t++) {
            if (MineField.isMine(session.layoutForTest(), t) == mine
                    && (session.readout(MineFieldSession.READOUT_REVEALED) >>> t & 1) == 0) {
                return t;
            }
        }
        throw new IllegalStateException("itemcasino gametest: no " + (mine ? "mine" : "safe tile") + " left");
    }

    /**
     * Two safe tiles and a cash-out pay the stake times the two-tile multiplier; a mine pays
     * nothing; a click aimed at a finished board, or at a tile already turned, changes nothing.
     */
    public static void mineFieldPaysWhatTheTilesEarned(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.MINE_FIELD.get());
        ServerPlayer player = seat(helper, table);
        MineFieldSession session = (MineFieldSession) table.session();
        session.setOption(player, 3);

        // --- a board cashed out after two safe tiles
        session.wagerContainer().setItem(0, ChipCards.newCard(100_000));
        session.setBet(player, 40);
        commit(session, player, session.placeWager(player));
        check(session.gameState() == GameState.ROLLING, "a new board is " + session.gameState());
        check(session.readout(MineFieldSession.READOUT_MINES) == 0, "the mines were shown during play");
        check(Integer.bitCount(session.layoutForTest()) == 3, "the board was laid with "
                + Integer.bitCount(session.layoutForTest()) + " mines, not 3");
        int token = MineFieldSession.token(session.sessionId());

        check(!session.reveal(player, token + 1, firstTile(session, false)), "a wrong token turned a tile");
        int first = firstTile(session, false);
        check(session.reveal(player, token, first), "a safe tile was refused");
        check(!session.reveal(player, token, first), "the same tile was turned twice");
        check(session.reveal(player, token, firstTile(session, false)), "a second safe tile was refused");
        check(session.revealedCount() == 2, "two tiles turned, " + session.revealedCount() + " counted");

        long multiplier = MineField.multiplierPpm(3, 2, com.itemcasino.CasinoConfig.SERVER.mineEdgePpm.get(),
                com.itemcasino.CasinoConfig.SERVER.mineMaxMultiplier.get() * MineField.PPM);
        check(session.cashOut(player, token), "the cash-out was refused");
        check(!session.cashOut(player, token), "a replayed cash-out was accepted");
        long balance = ChipCards.balance(session.wagerStack());
        long expected = 100_000 - 4_000 + Chips.payout(4_000, multiplier);
        check(balance == expected, "40 chips at x" + multiplier + "ppm left the card at " + balance + ", not " + expected);
        check(session.escrowView().isEmpty(), "the escrow survived a cash-out");
        check(session.gameState() == GameState.ARMED, "a cashed-out board is " + session.gameState());
        check(session.peekPayout().isEmpty(), "a chip cash-out left something to claim");
        noViolation(table, "after cashing out");
        check(!session.reveal(player, token, firstTile(session, false)), "a finished board turned a tile");

        // --- a board that finds a mine
        commit(session, player, session.placeWager(player));
        int second = MineFieldSession.token(session.sessionId());
        check(second != token, "two boards shared a token");
        check(!session.reveal(player, token, firstTile(session, false)), "the old board's token turned a tile");
        check(session.reveal(player, second, firstTile(session, true)), "a mine was refused");
        check(ChipCards.balance(session.wagerStack()) == balance - 4_000,
                "a mine left the card at " + ChipCards.balance(session.wagerStack()));
        check(session.escrowView().isEmpty(), "the escrow survived a mine");
        check(session.gameState() == GameState.ARMED, "a board that found a mine is " + session.gameState());
        check(session.readout(MineFieldSession.READOUT_MINES) == session.layoutForTest(),
                "the mines were not shown after the board ended");
        check(!session.cashOut(player, second), "a cash-out after a mine was accepted");
        noViolation(table, "after a mine");
        helper.succeed();
    }

    /** A board saved mid-play comes back cashed out at what its turned tiles had earned. */
    public static void mineFieldRestartCashesOut(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.MINE_FIELD.get());
        ServerPlayer player = seat(helper, table);
        MineFieldSession session = (MineFieldSession) table.session();
        session.setOption(player, 5);

        session.wagerContainer().setItem(0, ChipCards.newCard(50_000));
        session.setBet(player, 100);
        commit(session, player, session.placeWager(player));
        int token = MineFieldSession.token(session.sessionId());
        check(session.reveal(player, token, firstTile(session, false)), "a safe tile was refused");
        long owed = 50_000 - 10_000 + Chips.payout(10_000, session.currentMultiplierPpm());

        CompoundTag saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(table.getBlockPos(), table.getBlockState(),
                saved, helper.getLevel().registryAccess());
        check(loaded instanceof AbstractCasinoBlockEntity, "the table did not load back");
        MineFieldSession restored = (MineFieldSession) ((AbstractCasinoBlockEntity) loaded).session();

        check(!restored.gameState().holdsEscrow(), "a restored board is still holding the stake");
        check(restored.escrowView().isEmpty(), "a restored board kept its escrow");
        long cards = restored.peekPayout().stream().filter(ChipCards::isCard).count();
        long balance = restored.peekPayout().stream().filter(ChipCards::isCard).mapToLong(ChipCards::balance).sum();
        check(cards == 1 && balance == owed, "a board worth a card of " + owed + " came back as " + cards
                + " card(s) holding " + balance);
        check(restored.option() == 5, "the table forgot its mine count: " + restored.option());
        helper.succeed();
    }

    /**
     * A bet as large as the ceiling allows is taken, turns one tile, and the board cashes itself out
     * because the next tile would pay past the ceiling.
     */
    public static void mineFieldStopsAtTheCeiling(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.MINE_FIELD.get());
        ServerPlayer player = seat(helper, table);
        MineFieldSession session = (MineFieldSession) table.session();
        session.setOption(player, 3);
        int edge = com.itemcasino.CasinoConfig.SERVER.mineEdgePpm.get();
        long cap = com.itemcasino.CasinoConfig.SERVER.mineMaxMultiplier.get() * MineField.PPM;
        long ceiling = Chips.centsOfChips(com.itemcasino.CasinoConfig.SERVER.mineMaxPayoutChips.get());

        long cardCents = ceiling * 4;
        session.wagerContainer().setItem(0, ChipCards.newCard(cardCents));
        session.setBet(player, Long.MAX_VALUE / 200);
        long most = MineField.maxStakeCents(3, edge, cap, ceiling) / Chips.CENTS;
        check(session.effectiveBetChips() == most, "the table allowed a bet of " + session.effectiveBetChips()
                + " chips, not the " + most + " its ceiling allows");
        long stake = Chips.centsOfChips(most);
        check(MineField.nextTileOverCeiling(3, 1, edge, cap, stake, ceiling),
                "the default ceiling no longer stops a board of 3 mines after one tile; pick another case");

        commit(session, player, session.placeWager(player));
        int token = MineFieldSession.token(session.sessionId());
        check(session.reveal(player, token, firstTile(session, false)), "the first safe tile was refused");
        check(session.gameState() == GameState.ARMED, "the board went on past the ceiling: " + session.gameState());
        long expected = cardCents - stake + Chips.payout(stake, MineField.multiplierPpm(3, 1, edge, cap));
        check(ChipCards.balance(session.wagerStack()) == expected, "the card holds "
                + ChipCards.balance(session.wagerStack()) + " cents, not " + expected);
        check(Chips.payout(stake, MineField.multiplierPpm(3, 1, edge, cap)) <= ceiling, "the win passed the ceiling");
        helper.succeed();
    }

    // ------------------------------------------------------------------ chips

    /**
     * The cashier credits a stack at its value, charges the fee on the way out, refuses to overdraw
     * and merges a second card into the first.
     */
    public static void cashierExchangesAtValue(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        player.getAbilities().instabuild = false;
        player.getInventory().clearContent();
        CashierMenu menu = new CashierMenu(0, player.getInventory(),
                net.minecraft.world.inventory.ContainerLevelAccess.NULL, CashierMenu.Rates.current());
        var snapshot = com.itemcasino.valuation.ValuationEngine.snapshot();
        long diamond = com.itemcasino.valuation.StackValuator.unitValue(new ItemStack(Items.DIAMOND), snapshot);

        // Only currencies are exchanged: a stack of cobblestone stays where it is and credits nothing.
        menu.slots.get(CashierMenu.DEPOSIT_SLOT).set(new ItemStack(Items.COBBLESTONE, 64));
        menu.deposit(player);
        check(!ChipCards.isCard(menu.card()), "cobblestone was exchanged for a card");
        check(menu.depositStack().getCount() == 64, "refused cobblestone left the slot");
        check(!menu.slots.get(CashierMenu.DEPOSIT_SLOT).mayPlace(new ItemStack(Items.COBBLESTONE)),
                "the deposit slot accepts cobblestone");
        menu.slots.get(CashierMenu.DEPOSIT_SLOT).set(ItemStack.EMPTY);

        menu.slots.get(CashierMenu.DEPOSIT_SLOT).set(new ItemStack(Items.DIAMOND, 3));
        menu.deposit(player);
        long credited = Chips.centsForValue(diamond * 3);
        check(ChipCards.isCard(menu.card()), "a deposit with no card did not issue one");
        check(ChipCards.balance(menu.card()) == credited, "3 diamonds credited " + ChipCards.balance(menu.card())
                + " cents, not " + credited);
        check(menu.depositStack().isEmpty(), "the deposited diamonds are still in the slot");

        int index = -1;
        for (int i = 0; i < menu.rates().rates().size(); i++) {
            if (menu.rates().rates().get(i).item() == Items.DIAMOND) index = i;
        }
        check(index >= 0, "diamonds are not a cashier currency");
        menu.withdraw(player, index, 3);
        check(ChipCards.balance(menu.card()) == credited, "a withdrawal the card could not cover still charged it");

        long mailedBefore = mailboxCount(helper, player, Items.DIAMOND);
        menu.withdraw(player, index, 1);
        long cost = Chips.withdrawCost(diamond, com.itemcasino.CasinoConfig.SERVER.cashierFeePpm.get());
        check(ChipCards.balance(menu.card()) == credited - cost, "one diamond cost "
                + (credited - ChipCards.balance(menu.card())) + " cents, not " + cost);
        long received = countIn(player, Items.DIAMOND) + mailboxCount(helper, player, Items.DIAMOND) - mailedBefore;
        check(received == 1, "one diamond was paid as " + received);

        long before = ChipCards.balance(menu.card());
        menu.slots.get(CashierMenu.DEPOSIT_SLOT).set(ChipCards.newCard(12_345));
        menu.deposit(player);
        check(ChipCards.balance(menu.card()) == before + 12_345, "a second card was not merged into the first");
        check(menu.depositStack().isEmpty(), "the merged card is still in the deposit slot");
        helper.succeed();
    }

    /** A chip duel moves the loser's stake onto the winner's card, and hands each card back to its chair. */
    public static void chipDuelMovesChips(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.COIN_FLIP.get());
        ServerPlayer a = seat(helper, table);
        ServerPlayer b = seat(helper, table);
        CoinFlipSession session = (CoinFlipSession) table.session();

        session.wagerContainer().setItem(0, ChipCards.newCard(50_000));
        session.wagerContainerB().setItem(0, new ItemStack(Items.DIAMOND, 1));
        session.setBet(a, 100);
        check(!session.placeWager(a), "a duel of chips against a diamond was allowed");

        session.wagerContainerB().setItem(0, ChipCards.newCard(20_000));
        session.setBet(b, 100);
        check(session.placeWager(a), "the first duellist could not ready");
        check(session.placeWager(b), "the second duellist could not ready");
        check(session.gameState() == GameState.ROLLING, "the coin did not go up: " + session.gameState());
        session.finishFlip(session.sessionId());

        long cardA = ChipCards.balance(session.wagerStack());
        long cardB = ChipCards.balance(session.wagerStackB());
        boolean aWon = cardA == 60_000 && cardB == 10_000;
        boolean bWon = cardA == 40_000 && cardB == 30_000;
        check(aWon || bWon, "after a 100-chip duel the cards hold " + cardA + " and " + cardB);
        check(session.peekPayout().isEmpty(), "a chip duel left something to claim");
        check(session.escrowView().isEmpty(), "a chip duel kept its escrow");
        helper.succeed();
    }

    /** Chips offered at the Vault go into the pot's chips; the card is charged the offering and nothing else. */
    public static void vaultTakesChipOfferings(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.VAULT.get());
        ServerPlayer player = seat(helper, table);
        VaultSession session = (VaultSession) table.session();
        Jackpot jackpot = Jackpot.of(helper.getLevel());
        Jackpot.bank(helper.getLevel(), new ItemStack(Items.EMERALD_BLOCK), 50);
        jackpot.depositChips(1_000_000);

        session.wagerContainer().setItem(0, ChipCards.newCard(100_000));
        // At least the Vault's smallest offering (draw_min_value, 10 points), now that the setting is
        // compared in points: five chips used to pass only because it was compared in micro-points.
        session.setBet(player, 20);
        long chipsBefore = jackpot.chips();
        commit(session, player, session.placeWager(player));
        long offered = 2_000;
        long banked = com.itemcasino.core.game.VaultOdds.potPart(offered,
                com.itemcasino.CasinoConfig.SERVER.jackpotDrawPotSharePpm.get());
        // Only part of the offering is banked; a winning draw takes its share straight back out.
        check(jackpot.chips() == chipsBefore + banked || session.readout(VaultSession.READOUT_DRAW_PPM) > 0,
                "the pot holds " + (jackpot.chips() - chipsBefore) + " more cents, not the " + banked + " banked");
        session.finishDraw(session.sessionId());
        check(ChipCards.isCard(session.wagerStack()), "the card did not come back to the slot");
        long balance = ChipCards.balance(session.wagerStack());
        check(balance == 100_000 - offered || balance > 100_000 - offered,
                "the card was charged " + (100_000 - balance) + " cents for a 500-cent offering");
        helper.succeed();
    }

    // ------------------------------------------------------------------ regressions from the 2026-09-17 audit

    /**
     * A session torn down onto a player who cannot keep items (dead, or already disconnected) sends
     * them to the mailbox. A pocket game tears down when its menu closes, and a menu closes

     * when a dead player respawns and after a disconnecting player has been saved: adding to the
     * inventory then destroyed whatever was in the slot, a Chip Card included.
     */
    public static void liquidateKeepsWhatAPlayerCannotHold(GameTestHelper helper) {
        var mailbox = CasinoMailbox.of(helper.getLevel().getServer());

        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.DICE.get());
        ServerPlayer dead = seat(helper, table);
        dead.getInventory().clearContent();
        long mailedBefore = mailbox.pendingCount(dead.getUUID(), CasinoItems.CHIP_CARD.get());
        table.session().wagerContainer().setItem(0, ChipCards.newCard(12_300));
        dead.setHealth(0.0F);
        table.session().liquidate(dead);
        check(countIn(dead, CasinoItems.CHIP_CARD.get()) == 0, "a dead player's inventory was handed the card");
        check(mailbox.pendingCount(dead.getUUID(), CasinoItems.CHIP_CARD.get()) == mailedBefore + 1,
                "the dead player's card did not reach the mailbox");
        check(table.session().wagerStack().isEmpty(), "the card is still in the slot");
        dead.setHealth(dead.getMaxHealth());   // before the level ticks it through a death

        ServerPlayer gone = mockPlayer(helper);
        gone.getAbilities().instabuild = false;
        gone.getInventory().clearContent();
        long goneBefore = mailbox.pendingCount(gone.getUUID(), Items.DIAMOND);
        table.session().wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 5));
        gone.disconnect();
        table.session().liquidate(gone);
        check(countIn(gone, Items.DIAMOND) == 0, "a disconnected player's saved inventory was handed the stake");
        check(mailbox.pendingCount(gone.getUUID(), Items.DIAMOND) == goneBefore + 5,
                "the disconnected player's stake did not reach the mailbox");

        ServerPlayer alive = mockPlayer(helper);
        alive.getAbilities().instabuild = false;
        alive.getInventory().clearContent();
        table.session().wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 2));
        table.session().liquidate(alive);
        check(countIn(alive, Items.DIAMOND) == 2, "a living player did not get the stake straight back");
        helper.succeed();
    }

    /**
     * A duel saved while the coin was in the air pays the pot once, and breaking the table afterwards
     * hands back nothing more. The repair used to empty seat A's escrow and leave seat B's, which the
     * next teardown returned to its owner a second time.
     */
    public static void restoredDuelPaysOnce(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.COIN_FLIP.get());
        ServerPlayer a = seat(helper, table);
        ServerPlayer b = seat(helper, table);
        CoinFlipSession session = (CoinFlipSession) table.session();
        a.getInventory().clearContent();
        b.getInventory().clearContent();
        long mailedBefore = mailboxCount(helper, a, Items.DIAMOND) + mailboxCount(helper, b, Items.DIAMOND);

        session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 4));
        session.wagerContainerB().setItem(0, new ItemStack(Items.DIAMOND, 4));
        check(session.placeWager(a) && session.placeWager(b), "the duellists could not ready");
        check(session.gameState() == GameState.ROLLING, "the coin did not go up: " + session.gameState());

        CompoundTag saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(table.getBlockPos(), table.getBlockState(),
                saved, helper.getLevel().registryAccess());
        check(loaded instanceof AbstractCasinoBlockEntity, "the duel table did not load back");
        AbstractCasinoBlockEntity restoredTable = (AbstractCasinoBlockEntity) loaded;
        restoredTable.setLevel(helper.getLevel());
        CoinFlipSession restored = (CoinFlipSession) restoredTable.session();
        check(!restored.gameState().holdsEscrow(), "a restored duel is still holding the stakes");

        UUID winner = restored.payoutOwner();
        check(winner != null, "the restored duel has no named winner");
        restored.deliverPayout(winner.equals(a.getUUID()) ? a : b);
        restoredTable.spillEverything();

        long total = countIn(a, Items.DIAMOND) + countIn(b, Items.DIAMOND)
                + mailboxCount(helper, a, Items.DIAMOND) + mailboxCount(helper, b, Items.DIAMOND) - mailedBefore
                + countDropped(helper, helper.absolutePos(TABLE), Items.DIAMOND);
        check(total == 8, "a restored 4-against-4 duel handed out " + total + " diamonds");
        // The original table is still in the world with the same duel in flight. Stand it down (its
        // stakes go back to their owners) so it does not settle after the test has counted.
        session.liquidate(null);
        helper.succeed();
    }

    /**
     * A full pot makes room for a loss worth more than its cheapest entry, never keeps hidden worth,
     * and still refuses a newcomer worth less than everything it holds.
     */
    public static void fullPotMakesRoom(GameTestHelper helper) {
        Jackpot pot = new Jackpot();
        for (int damage = 1; damage <= Jackpot.MAX_ENTRIES; damage++) {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.setDamageValue(damage);
            pot.deposit(sword, 1);
        }
        check(pot.entries().size() == Jackpot.MAX_ENTRIES, "the pot holds " + pot.entries().size() + " kinds");
        check(pot.deposit(new ItemStack(Items.COBBLESTONE), 1) == 0, "a cobblestone pushed a sword out of a full pot");
        check(pot.deposit(new ItemStack(Items.DIAMOND), 3) == 3, "a full pot refused three diamonds");
        check(pot.countOf(Items.DIAMOND) == 3, "the diamonds are not in the pot");
        check(pot.entries().size() == Jackpot.MAX_ENTRIES, "the pot grew past its limit");
        check(pot.countOf(Items.DIAMOND_SWORD) == Jackpot.MAX_ENTRIES - 1, "the diamonds did not replace exactly one sword");

        Jackpot shared = Jackpot.of(helper.getLevel());
        long books = shared.countOf(Items.ENCHANTED_BOOK);
        Jackpot.bank(helper.getLevel(), new ItemStack(Items.ENCHANTED_BOOK), 2);
        check(shared.countOf(Items.ENCHANTED_BOOK) == books, "an enchanted book was banked at the price of a book");
        helper.succeed();
    }

    /** A stack bigger than the slot machine's ceiling is taken up to the ceiling; the rest stays put. */
    public static void slotMachineTakesItsCeiling(GameTestHelper helper) {
        AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.SLOT_MACHINE.get());
        ServerPlayer player = seat(helper, table);
        CasinoSession session = table.session();
        int most = com.itemcasino.session.SlotMachineSession.maxItemStake();
        check(most < 64, "the ceiling is a full stack; this test needs a smaller one");

        session.wagerContainer().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        commit(session, player, session.commitWager(player));
        check(session.escrowView().getCount() == most, "the machine took " + session.escrowView().getCount()
                + " of 64, not its ceiling of " + most);
        check(session.wagerStack().getCount() == 64 - most, "the slot kept " + session.wagerStack().getCount());
        noViolation(table, "after a partial stake");

        check(session.acknowledge(session.sessionId()), "the spin would not settle");
        check(session.escrowView().isEmpty(), "the escrow survived the spin");
        check(session.gameState() == GameState.ARMED, "the rest of the stack did not re-arm the machine: "
                + session.gameState());

        check(session.wagerStack().is(Items.IRON_INGOT) && session.wagerStack().getCount() == 64 - most,
                "the untaken part of the stack did not stay in the slot");
        helper.succeed();
    }

    /**
     * A settled hand holds its payout until the client has shown the cards; a test has no client, so
     * it answers for one. Checks on the way that nothing was paid before that.
     */

    private static void revealHand(BlackjackSession session) {
        if (session.gameState() != GameState.ROLLING) return;
        check(session.peekPayout().isEmpty(), "a hand paid before its cards were shown");
        check(session.finishReveal(session.sessionId()), "a settled hand would not finish its reveal");
    }

    private static long chipsIn(ServerPlayer player) {
        long total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            total += ChipCards.balance(player.getInventory().getItem(slot));
        }
        return total;
    }

    private static long chipsDropped(GameTestHelper helper, BlockPos absolute) {
        AABB box = new AABB(absolute).inflate(3.0D);
        long total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            total += ChipCards.balance(entity.getItem());
        }
        return total;
    }

    private static int countIn(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static long mailboxCount(GameTestHelper helper, ServerPlayer player, Item item) {
        // The mailbox is only used for owners who cannot receive; a mock player can, so this is
        // normally zero. Counted anyway so a regression that routes to the mailbox is not a leak.
        return CasinoMailbox.of(helper.getLevel().getServer()).pendingCount(player.getUUID(), item);
    }

    private static int countDropped(GameTestHelper helper, BlockPos absolute, Item item) {
        AABB box = new AABB(absolute).inflate(3.0D);
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            if (entity.getItem().is(item)) total += entity.getItem().getCount();
        }
        return total;
    }
}
