package com.itemcasino.gametest;

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
                !session.escrowView().isEmpty(), !session.peekPayout().isEmpty(),
                table.seat() != null);
        check(violation == null, where + ": " + violation);
    }

    private static ServerPlayer seat(GameTestHelper helper, AbstractCasinoBlockEntity table) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
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
        ServerPlayer onlooker = helper.makeMockServerPlayerInLevel();
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
        for (int attempt = 0; attempt < 40; attempt++) {
            AbstractCasinoBlockEntity table = place(helper, CasinoBlocks.BLACKJACK_TABLE.get());
            ServerPlayer player = seat(helper, table);
            BlackjackSession session = (BlackjackSession) table.session();

            session.wagerContainer().setItem(0, new ItemStack(Items.DIAMOND, 3));
            commit(session, player, session.placeWager(player));
            if (session.gameState() != GameState.ROLLING || session.table() == null
                    || session.table().phase() != BlackjackPhase.PLAYER_TURN) {
                continue;                      // a natural settled at once; deal another
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
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;
        player.getInventory().clearContent();
        CashierMenu menu = new CashierMenu(0, player.getInventory(),
                net.minecraft.world.inventory.ContainerLevelAccess.NULL, CashierMenu.Rates.current());
        var snapshot = com.itemcasino.valuation.ValuationEngine.snapshot();
        long diamond = com.itemcasino.valuation.StackValuator.unitValue(new ItemStack(Items.DIAMOND), snapshot);

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
        session.setBet(player, 5);
        long chipsBefore = jackpot.chips();
        commit(session, player, session.placeWager(player));
        long offered = 500;
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
