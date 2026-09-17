package com.itemcasino.session;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.VaultOdds;
import com.itemcasino.core.game.jackpot.JackpotOdds;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.jackpot.Jackpot;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.network.s2c.S2CVaultDraw;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Vault: feed the pot, name the share of it you are playing for, and buy a chance at that share.
 *
 * <h2>The deal</h2>
 * Your offering goes in first and your chance is worked out against the prize <em>including</em> it
 * — the share you named, of every kind in the pot, rounded down to whole items. That makes a draw
 * worth a fixed fraction of what you fed it no matter how rich the pot has grown or how much of it
 * you asked for — see {@link VaultOdds}. Ask for all of it and you buy the long shot; ask for a tenth
 * and the same offering buys ten times the chance. What you offer is gone either way; that is what
 * makes it a wager rather than a lottery ticket you get refunded.
 *
 * <h2>Why the draw happens before the animation</h2>
 * Everywhere else in this mod the outcome is decided at commit and replayed. Here that principle
 * has teeth: the pot is server-wide state, and if the award waited for the animation, a crash mid
 * -spin would leave the question of whether it had been paid — with the whole pot riding on the
 * answer. Deciding and awarding in the same tick as the commit makes that unanswerable question
 * impossible: either the pot moved, or it did not.
 */
public class VaultSession extends CasinoSession {

    public static final int READOUT_DRAW_PPM = 0;
    public static final int READOUT_PRIZE_MILLI = 1;
    /** The draw's return and ceiling, in hundredths of a percent, packed {@code return << 16 | max}. */
    public static final int READOUT_TERMS = 2;
    /** How much of an offering goes into the pot, in hundredths of a percent. */
    public static final int READOUT_POT_SHARE = 3;

    private int drawPpm;
    private boolean tookIt;
    /** The share of the pot this table is playing for, 1..100. A table setting, like the dice bet. */
    private int sharePercent = VaultOdds.MAX_SHARE;
    /** What the committed draw was playing for, frozen with the draw. */
    private long prizeValue;
    /** The chips a won draw handed over, credited to the card when the draw settles. */
    private long wonChipCents;
    /**
     * The share this draw won, already out of the pot and not yet handed over. It is taken at commit
     * so no other table can win the same items, and delivered at the settle, once the dial has
     * stopped: a prize that lands in the inventory and in the chat while the pointer is still turning
     * tells the player the result before the Vault does.
     */
    @javax.annotation.Nullable private Jackpot.Prize prize;
    /** What the draw offered, for the stats written at the settle. */
    private long offeredValue;

    /**
     * One quote per tick. The container data asks for the odds, the prize and the offering
     * separately, and each of them walks the whole pot; pricing it once a tick is plenty.
     */
    private long quoteTick = Long.MIN_VALUE;
    private ItemStack quoteStack = ItemStack.EMPTY;
    private int quoteShare = -1;
    private Quote quote = Quote.NONE;

    private record Quote(ItemStack taken, long offered, long prize, int ppm) {
        static final Quote NONE = new Quote(ItemStack.EMPTY, -1, -1, 0);
    }

    public VaultSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_VAULT; }

    /** The share of the pot being played for, in percent. */
    @Override
    public int option() {
        return sharePercent;
    }

    public int sharePercent() { return sharePercent; }

    /**
     * The player names their share. Refused once the draw is committed: the odds were priced against
     * the share, and a share you could revise afterwards would be a different bet.
     */
    @Override
    public void setOption(ServerPlayer player, int value) {
        if (!state.acceptsItems()) return;
        int clamped = VaultOdds.clampShare(value);
        if (clamped == sharePercent) return;
        sharePercent = clamped;
        quoteTick = Long.MIN_VALUE;
        host.markDirty();
    }

    /** The pot's worth, and the useful part of the offering, in thousandths. */
    @Override
    public int stakeMilli(int seat) {
        return milli(seat == 0 ? displayedPotValue() : currentQuote().offered());
    }

    private long potValueTick = Long.MIN_VALUE;
    private long potValue;

    /**
     * The pot's worth for the screen, priced once a tick. The data channel asks for it once per
     * viewer per tick, and each answer walked the whole pot. Never used to price a draw.
     */
    private long displayedPotValue() {
        long now = host.hostLevel().getGameTime();
        if (now != potValueTick) {
            potValue = Jackpot.of(host.hostLevel()).totalValue(ValuationEngine.snapshot());
            potValueTick = now;
        }
        return potValue;
    }

    /** Odds, prize and ceiling, for the screen. */
    @Override
    public int readout(int index) {
        return switch (index) {
            case READOUT_DRAW_PPM -> state.acceptsItems() ? currentQuote().ppm() : drawPpm;
            case READOUT_PRIZE_MILLI -> milli(state.acceptsItems() ? currentQuote().prize() : prizeValue);
            case READOUT_TERMS -> packTerms(CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                    CasinoConfig.SERVER.jackpotDrawMaxPpm.get());
            case READOUT_POT_SHARE -> potSharePpm() / 100;
            default -> 0;
        };
    }

    /** Both terms fit in 16 bits at 0.01 % steps: the return tops out at 10 000, the ceiling at 9 000. */
    public static int packTerms(int returnPpm, int maxPpm) {
        return (Math.min(0xFFFF, returnPpm / 100) << 16) | Math.min(0xFFFF, maxPpm / 100);
    }

    private static int milli(long value) {
        if (value < 0 || value == Fixed.INF) return -1;
        // Rounded up from zero: a prize worth less than a thousandth is still a prize, and the
        // screen reads 0 as "this share hands over nothing".
        if (value > 0 && value < 1000L) return 1;
        return (int) Math.min(Integer.MAX_VALUE, value / 1000L);
    }

    private Quote currentQuote() {
        long now = host.hostLevel().getGameTime();
        ItemStack stack = wagerStack();
        if (now != quoteTick || quoteShare != sharePercent
                || !ItemStack.matches(stack, quoteStack)) {
            quote = quote(stack);
            quoteTick = now;
            quoteShare = sharePercent;
            quoteStack = stack.copy();
        }
        return quote;
    }

    private static int potSharePpm() {
        return CasinoConfig.SERVER.jackpotDrawPotSharePpm.get();
    }

    /** The part of an item offering that goes into the pot; empty when it rounds down to nothing. */
    private static ItemStack banked(ItemStack taken) {
        long count = VaultOdds.potPart(taken.getCount(), potSharePpm());
        return count <= 0 ? ItemStack.EMPTY : taken.copyWithCount((int) count);
    }

    private long offeredValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        long value = StackValuator.value(stack, ValuationEngine.snapshot());
        return value == Fixed.INF ? -1 : value;
    }

    /** What this offering would buy, against the share of the pot as it would be once it is in. */
    private Quote quote(ItemStack stack) {
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (stack.isEmpty()) {
            // Nothing offered yet, but the share is already chosen: show what it would hand over.
            return new Quote(ItemStack.EMPTY, -1,
                    Jackpot.of(host.hostLevel()).prizeValue(snapshot, sharePercent), 0);
        }
        if (com.itemcasino.chips.ChipCards.isCard(stack)) {
            long cents = Chips.centsOfChips(usefulChips());
            long offered = cents > 0 ? Chips.valueOfCents(cents) : -1;
            if (offered <= 0) return new Quote(ItemStack.EMPTY, offered, -1, 0);
            long prize = Jackpot.of(host.hostLevel()).prizeValueWith(ItemStack.EMPTY,
                    VaultOdds.potPart(cents, potSharePpm()), snapshot, sharePercent);
            int ppm = VaultOdds.drawPpm(offered, prize,
                    CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                    CasinoConfig.SERVER.jackpotDrawMaxPpm.get());
            return new Quote(ItemStack.EMPTY, offered, prize, ppm);
        }
        ItemStack taken = usefulPart(stack);
        long offered = offeredValue(taken);
        if (offered <= 0) return new Quote(taken, offered, -1, 0);
        long prize = Jackpot.of(host.hostLevel()).prizeValueWith(banked(taken), snapshot, sharePercent);
        int ppm = VaultOdds.drawPpm(offered, prize,
                CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                CasinoConfig.SERVER.jackpotDrawMaxPpm.get());
        return new Quote(taken, offered, prize, ppm);
    }

    /**
     * Trims an offering down to the part that actually buys something.
     *
     * <p>Past a certain size the odds hit their ceiling and every further item is taken for nothing
     * — which is what "1 in 20, always" on the screen was really telling us. Rather than letting a
     * player overpay for a number that has stopped moving, the Vault takes only the useful part and
     * leaves the rest in the slot.
     */
    public ItemStack usefulPart(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        long unit = StackValuator.unitValue(stack, snapshot);
        if (unit == Fixed.INF || unit <= 0) return stack;

        long pot = Jackpot.of(host.hostLevel()).totalValue(snapshot);
        long useful = VaultOdds.maxUsefulOffer(pot, sharePercent,
                CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                CasinoConfig.SERVER.jackpotDrawMaxPpm.get(), potSharePpm());
        if (useful == Long.MAX_VALUE) return stack;

        long affordable = Math.max(1, useful / unit);
        if (affordable >= stack.getCount()) return stack;
        ItemStack trimmed = stack.copy();
        trimmed.setCount((int) affordable);
        return trimmed;
    }

    /** The chips of the bet that buy something: the same trim as {@link #usefulPart}, for a card. */
    public long usefulChips() {
        long bet = effectiveBetChips();
        if (bet <= 0) return 0;
        long pot = Jackpot.of(host.hostLevel()).totalValue(ValuationEngine.snapshot());
        long useful = VaultOdds.maxUsefulOffer(pot, sharePercent,
                CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                CasinoConfig.SERVER.jackpotDrawMaxPpm.get(), potSharePpm());
        if (useful == Long.MAX_VALUE) return bet;
        long usefulChips = Math.max(1, (useful + Fixed.ONE - 1) / Fixed.ONE);
        return Math.min(bet, usefulChips);
    }

    /** How many items the Vault would actually take, so the screen can say so before the click. */
    public int usefulCount() {
        return usefulPart(wagerStack()).getCount();
    }

    @Override
    protected void onWagerChanged() {
        quoteTick = Long.MIN_VALUE;
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        setState(wagerStack().isEmpty() ? GameState.IDLE : GameState.ARMED);
    }

    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.jackpotEnabled.get()) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.no_jackpot"), true);
            return false;
        }
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        ItemStack stack = wagerStack();
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!checkStake(player, snapshot)) return false;
        if (com.itemcasino.chips.ChipCards.isCard(stack)) return placeChipWager(player, snapshot);
        // The pot is priced by the same engine as the offering, which cannot see what a potion or an
        // enchanted book carries in its data. Banked at the bare item's price, that hidden worth
        // would be sold on by the next draw for a fraction of what it is.
        if (com.itemcasino.valuation.ItemFilter.isComponentDriven(stack)) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.component_driven"), true);
            return false;
        }
        // Only the useful part is taken; the rest stays in the slot rather than being swallowed
        // for a chance that was already at its ceiling.
        ItemStack taken = usefulPart(stack);

        long offered = offeredValue(taken);
        // Values are micro-points; the setting is in points. Compared raw, "10" meant a hundred
        // thousandth of a point and every offering passed.
        if (offered < Fixed.ofPoints(CasinoConfig.SERVER.jackpotDrawMinValue.get())) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.offering_too_small"),
                    true);
            return false;
        }

        // A full pot refuses a new kind of item, and an offering it cannot bank would simply be
        // destroyed while the odds were worked out as if it had gone in.
        ItemStack banked = banked(taken);
        if (!banked.isEmpty() && !Jackpot.of(host.hostLevel()).canDeposit(banked)) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.vault_full"), true);
            return false;
        }
        // A share that rounds down to no whole item of anything would take the offering for a
        // prize of nothing. Refused before a single item moves.
        if (Jackpot.of(host.hostLevel()).prizeValueWith(banked, snapshot, sharePercent) <= 0) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.share_too_small"),
                    true);
            return false;
        }

        this.frozenSnapshot = snapshot;
        this.sessionId++;
        stakeCents = 0;
        wonChipCents = 0;
        escrowPartial(taken.getCount());
        if (!setState(GameState.LOCKED)) return false;

        Jackpot jackpot = Jackpot.of(host.hostLevel());
        // Into the pot first, so the odds below are measured against a pot that already contains
        // it. Only part of it: the rest leaves the game with the escrow at the settle.
        if (!banked.isEmpty()) jackpot.deposit(escrow, banked.getCount());
        return draw(player, snapshot, jackpot, offered);
    }

    /**
     * The same draw for an offering of chips: the card goes into escrow whole, the useful chips
     * go into the pot, and whatever share of the pot's chips is won comes back onto that card.
     */
    private boolean placeChipWager(ServerPlayer player, ValuationSnapshot snapshot) {
        long chips = usefulChips();
        long cents = Chips.centsOfChips(chips);
        long offered = Chips.valueOfCents(cents);
        if (offered < Fixed.ofPoints(CasinoConfig.SERVER.jackpotDrawMinValue.get())) {

            player.displayClientMessage(Component.translatable("itemcasino.reject.offering_too_small"), true);
            return false;
        }
        if (Jackpot.of(host.hostLevel()).prizeValueWith(ItemStack.EMPTY,
                VaultOdds.potPart(cents, potSharePpm()), snapshot, sharePercent) <= 0) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.share_too_small"), true);
            return false;
        }

        this.frozenSnapshot = snapshot;
        this.sessionId++;
        escrowStake();
        stakeCents = cents;           // only the useful part of the bet is staked
        wonChipCents = 0;
        if (!setState(GameState.LOCKED)) return false;

        Jackpot jackpot = Jackpot.of(host.hostLevel());
        jackpot.depositChips(VaultOdds.potPart(stakeCents, potSharePpm()));
        return draw(player, snapshot, jackpot, offered);
    }

    /** Prices, rolls and — on a win — pays the draw, against a pot the offering is already in. */
    private boolean draw(ServerPlayer player, ValuationSnapshot snapshot, Jackpot jackpot, long offered) {
        long pot = jackpot.totalValue(snapshot);
        this.prizeValue = jackpot.prizeValue(snapshot, sharePercent);
        this.drawPpm = VaultOdds.drawPpm(offered, prizeValue,
                CasinoConfig.SERVER.jackpotDrawReturnPpm.get(),
                CasinoConfig.SERVER.jackpotDrawMaxPpm.get());
        this.frozenPpm = drawPpm;

        this.tookIt = host.random().nextInt(JackpotOdds.PPM) < drawPpm;
        this.decidedWin = tookIt;
        this.spinTicks = CasinoConfig.SERVER.vaultDrawTicks.get();
        setState(GameState.ROLLING);
        this.deadlineTick = host.hostLevel().getGameTime() + spinTicks + 40L;
        beginCommit(player, spinTicks);
        touch();

        prize = tookIt ? jackpot.takeShare(player.getName().getString(), sharePercent) : null;
        wonChipCents = prize != null && stakeIsChips() ? prize.chipCents() : 0L;
        offeredValue = offered;
        long won = prize == null ? 0L : prize.value();

        ItemCasino.AUDIT.info("[jackpot] {} drew at {}ppm for {}% ({}) of a pot of {} -- {}",
                player.getName().getString(), drawPpm, sharePercent, Fixed.format(prizeValue),
                Fixed.format(pot), tookIt ? "TOOK IT" : "nothing");

        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), drawPpm));
        host.broadcast(id -> new S2CVaultDraw(id, sessionId, tookIt, drawPpm, spinTicks));
        return true;
    }

    @Override
    public boolean commitWager(ServerPlayer player) { return placeWager(player); }

    @Override
    public boolean acknowledge(long claimedSession) { return finishDraw(claimedSession); }

    /** The offering went into the pot at commit; the settle has nothing further to bank. */
    @Override
    protected boolean banksLosses() { return false; }

    public boolean finishDraw(long claimedSession) {

        if (state != GameState.ROLLING) return false;
        if (claimedSession != 0 && claimedSession != sessionId) return false;
        settle();
        return true;
    }

    @Override
    public void forceSettle() {
        if (state == GameState.ROLLING) settle();
    }

    private void settle() {
        if (!setState(GameState.SETTLING)) return;
        materialiseDecidedOutcome();
        handOverPrize();
        recordOutcome(offeredValue, prize == null ? 0L : prize.value());
        // Not banked through bankLoss: the offering went into the pot at commit rather than being
        // consumed, so counting it as a loss here would bank it a second time.
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        rearmIfStakeLeft();

        touch();
        broadcastPayout();
        prize = null;
        host.markDirty();
    }

    /** The prize's items to the winner, its chips onto the card or onto a card of their own. */
    private void handOverPrize() {
        if (prize == null || prize.isEmpty()) return;
        java.util.UUID owner = wagerOwner;
        net.minecraft.server.MinecraftServer server = host.hostLevel().getServer();
        if (owner == null || server == null) {
            // Nobody to name: the prize waits in the table for whoever holds the seat.
            payout.addAll(prize.stacks());
            if (!stakeIsChips() && prize.chipCents() > 0) payout.add(com.itemcasino.chips.ChipCards.newCard(prize.chipCents()));
            return;
        }
        Jackpot.deliver(server, owner, prize);
        if (!stakeIsChips() && prize.chipCents() > 0) {
            // An item offering has no card on the table: the chips come on a card of their own.
            com.itemcasino.player.CasinoMailbox.send(server, owner, com.itemcasino.chips.ChipCards.newCard(prize.chipCents()));
        }
    }

    @Override
    protected java.util.List<ItemStack> bannerStacks() {
        return prize == null ? payout : prize.stacks();
    }

    @Override
    protected long winCents() {
        return prize == null ? 0L : prize.chipCents();
    }

    @Override
    protected byte winTier() {
        return com.itemcasino.network.s2c.S2CPayoutReady.TIER_JACKPOT;
    }

    /**
     * A draw saved while the dial was turning: its prize was already out of the pot, so it is parked
     * in the table to be collected rather than delivered from a load, which has no server to hand.
     */
    @Override
    protected void repairAfterLoad() {
        boolean holding = state.holdsEscrow();
        boolean chipOffering = stakeCents > 0 && com.itemcasino.chips.ChipCards.isCard(escrow);
        super.repairAfterLoad();
        if (holding) {
            // The base class booked the offering against the chips it paid; a draw also pays items,
            // and a won draw is a jackpot in the stats.
            final java.util.UUID owner = wagerOwner;
            final long offered = offeredValue;
            final long won = prize == null ? 0L : prize.value();
            restoredBookkeeping = level -> {
                recordOutcome(owner, offered, won);
                if (owner != null && won > 0) {
                    com.itemcasino.player.CasinoStats.recordJackpot(level.getServer(), owner, won);
                }
            };
        }
        if (!holding || prize == null || prize.isEmpty()) {
            prize = null;
            return;
        }
        payout.addAll(prize.stacks());
        if (!chipOffering && prize.chipCents() > 0) payout.add(com.itemcasino.chips.ChipCards.newCard(prize.chipCents()));
        if (!payout.isEmpty() && state == GameState.IDLE) state = GameState.PAYOUT_PENDING;
        prize = null;
    }

    /**
     * Nothing. The pot was handed over at commit if it was won at all, and the offering belongs to
     * the pot either way — there is never anything left in the buffer to collect.
     */
    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        // A card offered chips: they are in the pot already, and any chips won come back on it.
        payChips(wonChipCents);
    }

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.putInt("draw_ppm", drawPpm);
        out.putBoolean("took_it", tookIt);
        out.putInt("share_percent", sharePercent);
        out.putLong("prize_value", prizeValue);
        out.putLong("won_chip_cents", wonChipCents);
        out.putLong("offered_value", offeredValue);
        if (prize != null && !prize.isEmpty()) {
            out.putString("prize_winner", prize.winner());
            out.putInt("prize_share", prize.share());
            out.putLong("won_prize_value", prize.value());
            out.putLong("prize_chip_cents", prize.chipCents());
            out.store("prize_stacks", ItemStack.CODEC.listOf(), prize.stacks());
        }
    }

    @Override
    public void load(ValueInput in) {
        drawPpm = in.getIntOr("draw_ppm", 0);
        tookIt = in.getBooleanOr("took_it", false);
        sharePercent = VaultOdds.clampShare(in.getIntOr("share_percent", VaultOdds.MAX_SHARE));
        prizeValue = in.getLongOr("prize_value", 0L);
        wonChipCents = Math.max(0, in.getLongOr("won_chip_cents", 0L));
        offeredValue = Math.max(0, in.getLongOr("offered_value", 0L));
        java.util.List<ItemStack> prizeStacks = in.read("prize_stacks", ItemStack.CODEC.listOf()).orElse(java.util.List.of());
        long prizeChips = Math.max(0, in.getLongOr("prize_chip_cents", 0L));
        prize = prizeStacks.isEmpty() && prizeChips <= 0 ? null
                : new Jackpot.Prize(in.getStringOr("prize_winner", ""), in.getIntOr("prize_share", 100),
                        Math.max(0, in.getLongOr("won_prize_value", 0L)), prizeChips, java.util.List.copyOf(prizeStacks));
        super.load(in);
    }
}
