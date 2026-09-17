package com.itemcasino;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Configuration, split by trust boundary.
 *
 * <p>{@link Server} carries every number that decides an outcome or a value. It is a SERVER config
 * on purpose: a client may never influence it, and it is per-world so a pack can ship balance.
 * {@link Common} carries constants shared with datagen.
 */
public final class CasinoConfig {

    /** The names {@code safety.disabled_games} accepts, one per game type (by its byte id) plus the cashier. */
    public static final List<String> GAME_IDS = List.of(
            "upgrader", "dice", "blackjack", "coin_flip", "slot_machine", "vault", "mine_field", "cashier");

    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;
    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;

    static {
        Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        Pair<Common, ModConfigSpec> common = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();
    }

    private CasinoConfig() {}

    /**
     * Whether the server switched this game off. {@code gameType} is the byte every session reports
     * ({@code S2CSessionStarted.GAME_*}), which is also its index in {@link #GAME_IDS}.
     */
    public static boolean isGameDisabled(int gameType) {
        return gameType >= 0 && gameType < GAME_IDS.size() && isDisabled(GAME_IDS.get(gameType));
    }

    public static boolean isDisabled(String id) {
        return SERVER.disabledGames.get().contains(id);
    }

    public static final class Server {

        // --- valuation ----------------------------------------------------
        public final ModConfigSpec.DoubleValue durabilityFloor;
        public final ModConfigSpec.BooleanValue valueEnchantments;
        public final ModConfigSpec.IntValue enchantLevelValue;
        public final ModConfigSpec.IntValue smeltingFuelCost;
        public final ModConfigSpec.IntValue rarityCommon;
        public final ModConfigSpec.IntValue rarityUncommon;
        public final ModConfigSpec.IntValue rarityRare;
        public final ModConfigSpec.IntValue rarityEpic;
        public final ModConfigSpec.BooleanValue estimateUnknownItems;
        public final ModConfigSpec.ConfigValue<List<? extends String>> valueOverrides;
        public final ModConfigSpec.IntValue maxIngredientOptions;

        // --- wagering -----------------------------------------------------
        public final ModConfigSpec.BooleanValue allowCreative;
        public final ModConfigSpec.IntValue minWagerValuePoints;
        public final ModConfigSpec.BooleanValue refuseComponentDrivenInput;
        public final ModConfigSpec.ConfigValue<String> rounding;

        // --- upgrader -----------------------------------------------------
        public final ModConfigSpec.IntValue wheelSpinTicks;

        // --- predict the dice ---------------------------------------------
        public final ModConfigSpec.IntValue cashierFeePpm;
        public final ModConfigSpec.ConfigValue<List<? extends String>> cashierCurrencies;
        public final ModConfigSpec.IntValue diceEdgePpm;
        public final ModConfigSpec.IntValue diceMinChance;
        public final ModConfigSpec.IntValue diceMaxChance;
        public final ModConfigSpec.IntValue diceRollTicks;
        public final ModConfigSpec.IntValue diceMaxPayoutChips;


        // --- the jackpot ----------------------------------------------------
        public final ModConfigSpec.BooleanValue jackpotEnabled;

        public final ModConfigSpec.IntValue jackpotDrawReturnPpm;
        public final ModConfigSpec.IntValue jackpotDrawMaxPpm;
        public final ModConfigSpec.IntValue jackpotDrawMinValue;
        public final ModConfigSpec.IntValue jackpotDrawPotSharePpm;

        // --- mine field -----------------------------------------------------
        public final ModConfigSpec.IntValue mineEdgePpm;
        public final ModConfigSpec.IntValue mineMaxMultiplier;
        public final ModConfigSpec.IntValue mineMaxPayoutChips;
        public final ModConfigSpec.IntValue mineDefaultMines;
        public final ModConfigSpec.IntValue mineActionSeconds;

        // --- slot machine ---------------------------------------------------
        public final ModConfigSpec.IntValue slotSpinTicks;
        public final ModConfigSpec.IntValue slotReelStagger;
        public final ModConfigSpec.IntValue slotMaxStake;
        public final ModConfigSpec.IntValue slotMaxStakeChips;
        public final ModConfigSpec.IntValue vaultDrawTicks;

        // --- coin flip ------------------------------------------------------
        public final ModConfigSpec.IntValue coinFlipTolerancePpm;
        public final ModConfigSpec.IntValue coinFlipTicks;

        // --- blackjack ----------------------------------------------------
        public final ModConfigSpec.IntValue blackjackDecks;
        public final ModConfigSpec.BooleanValue dealerHitsSoft17;
        public final ModConfigSpec.BooleanValue allowDouble;
        public final ModConfigSpec.BooleanValue allowSurrender;
        public final ModConfigSpec.BooleanValue dealerPeek;
        public final ModConfigSpec.IntValue playerActionSeconds;

        // --- safety -------------------------------------------------------
        public final ModConfigSpec.IntValue packetsPerSecond;
        public final ModConfigSpec.IntValue abandonSeconds;
        public final ModConfigSpec.BooleanValue protectBlockDuringWager;
        public final ModConfigSpec.BooleanValue logSettlements;
        public final ModConfigSpec.ConfigValue<List<? extends String>> disabledGames;

        private Server(ModConfigSpec.Builder b) {
            b.comment("Item valuation").push("valuation");
            durabilityFloor = b
                    .comment("Fraction of an item's value retained at zero durability.",
                             "Without a floor, a nearly-broken netherite tool becomes a free lottery ticket.")
                    .defineInRange("durability_floor", 0.15D, 0.0D, 1.0D);
            valueEnchantments = b
                    .comment("Add value for enchantments on a wagered item.")
                    .define("value_enchantments", false);
            enchantLevelValue = b
                    .comment("Value points added per enchantment level when the above is enabled.")
                    .defineInRange("enchant_level_value", 8, 0, 100000);
            smeltingFuelCost = b
                    .comment("Value points charged per smelt/blast/smoke/campfire operation.",
                             "PlacementInfo cannot see fuel, so it is added as a flat surcharge. 0 disables.")
                    .defineInRange("smelting_fuel_cost", 1, 0, 100000);
            // Renamed from fallback_* on purpose: the old defaults (16 for a common item) made
            // every infinite block worth more than iron, and a renamed key is the only way an
            // existing world's config picks up the new default instead of keeping the broken one.
            rarityCommon = b
                    .comment("Estimated value, by rarity, of an item no base value or recipe can price.",
                             "Estimated items may be wagered at this value but are never Upgrader targets,",
                             "so keep these low: a high estimate turns junk into a good wager.")
                    .defineInRange("estimate_common", 1, 0, 100000000);
            rarityUncommon = b.defineInRange("estimate_uncommon", 8, 0, 100000000);
            rarityRare = b.defineInRange("estimate_rare", 32, 0, 100000000);
            rarityEpic = b.defineInRange("estimate_epic", 128, 0, 100000000);
            estimateUnknownItems = b
                    .comment("Estimate items that nothing can price (see estimate_*).",
                             "Disable to make them unwagerable instead.")
                    .define("estimate_unknown_items", true);
            valueOverrides = b
                    .comment("Hard overrides, 'namespace:item=points' or 'namespace:item=points!' to pin.",
                             "A pinned value survives even when a cheaper recipe exists.")
                    .defineList("value_overrides",
                            List.of("minecraft:nether_star=20000!", "minecraft:dragon_egg=50000!"),
                            () -> "minecraft:stone=1",
                            o -> o instanceof String s && s.contains("="));
            maxIngredientOptions = b
                    .comment("Cap on how many alternatives of one tag ingredient are considered.")
                    .defineInRange("max_ingredient_options", 1024, 1, 4096);
            b.pop();

            b.comment("Wagering rules").push("wagering");
            allowCreative = b.define("allow_creative", false);
            minWagerValuePoints = b
                    .comment("Refuse wagers worth less than this many value points.")
                    .defineInRange("min_wager_value", 1, 0, 100000000);
            refuseComponentDrivenInput = b
                    .comment("Refuse items whose worth is in their data components (potions,",
                             "enchanted books, shulker boxes). When false they are accepted but",
                             "valued as the bare item, and the GUI says so.")
                    .define("refuse_component_driven_input", false);
            rounding = b
                    .comment("Sub-item payout remainder policy: DISCARD, NEAREST, UP or CHANGE.")
                    .define("rounding", "CHANGE");
            b.pop();

            b.comment("Upgrader wheel").push("upgrader");
            wheelSpinTicks = b.defineInRange("spin_ticks", 150, 10, 600);
            b.pop();

            b.comment("Predict the Dice").push("dice");
            diceEdgePpm = b
                    .comment("The house's share of every multiplier, in parts per million. 30000 = 3%.",
                            "Every bet, whatever its odds, returns 1 - edge of the stake on average.")
                    .defineInRange("edge_ppm", 30_000, 0, 500_000);
            diceMinChance = b
                    .comment("Smallest chance a player may bet on, in hundredths of a percent. 100 = 1%.")
                    .defineInRange("min_chance", 100, 1, 9_999);
            diceMaxChance = b
                    .comment("Largest chance a player may bet on, in hundredths of a percent. 9500 = 95%.")
                    .defineInRange("max_chance", 9_500, 1, 9_999);
            diceRollTicks = b.defineInRange("roll_ticks", 50, 10, 600);
            diceMaxPayoutChips = b
                    .comment("Largest win, in chips, one roll may pay. The bet allowed shrinks as the",
                            "multiplier grows: 125000 chips at 48.5% (x2), 2577 at 1% (x97).")
                    .defineInRange("max_payout_chips", 250_000, 1, 1_000_000_000);
            b.pop();

            b.comment("Chips and the cashier").push("chips");
            cashierFeePpm = b
                    .comment("The cashier's cut when chips are changed back into items, in parts per million.",
                            "20000 = 2%. Depositing is always at the full value.")
                    .defineInRange("withdraw_fee_ppm", 20_000, 0, 500_000);
            cashierCurrencies = b
                    .comment("What the cashier pays chips out in, in this order. At most eight.")
                    .defineList("currencies",
                            List.of("minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot",
                                    "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_nugget",
                                    "minecraft:iron_nugget"),
                            () -> "minecraft:diamond",
                            o -> o instanceof String s && s.contains(":"));
            b.pop();


            b.comment("The shared jackpot").push("jackpot");
            jackpotEnabled = b
                    .comment("Bank what players lose into one server-wide pot.",
                            "Turn this off and losses simply cease to exist, as they used to.")
                    .define("enabled", true);

            jackpotDrawReturnPpm = b
                    .comment("What a bought draw at the Vault returns, in expectation,",
                            "as a fraction of what was fed into it. 800000 = 80%.")
                    .defineInRange("draw_return_ppm", 800_000, 1, 1_000_000);
            // Renamed from draw_max_ppm: the old 5% default predates choosing a share of the pot,
            // and a renamed key is how an existing world picks up the new one.
            jackpotDrawMaxPpm = b
                    .comment("Ceiling on a single draw, however much is offered. 500000 = 50%.",
                            "Players name the share of the pot they play for, so a small share with a",
                            "large offering can be likely; this is how likely it is allowed to get.")
                    .defineInRange("draw_max_chance_ppm", 500_000, 1, 900_000);
            jackpotDrawMinValue = b
                    .comment("Smallest offering the Vault will accept, in value points (1 chip = 1 point).")
                    .defineInRange("draw_min_value", 10, 1, 100_000_000);
            jackpotDrawPotSharePpm = b
                    .comment("How much of a Vault offering goes into the pot; the rest is gone for good.",
                            "500000 = half. Keeps the pot from being fed by the very draws that try to empty it.")
                    .defineInRange("draw_pot_share_ppm", 500_000, 0, 1_000_000);
            vaultDrawTicks = b
                    .comment("How long the Vault dial turns before it says.")
                    .defineInRange("draw_ticks", 90, 10, 600);
            b.pop();

            b.comment("Mine field").push("mine_field");
            mineEdgePpm = b
                    .comment("The house's share of every multiplier, in parts per million. 30000 = 3%.",
                             "Cashing out after any number of tiles returns 1 - edge of the stake on average.")
                    .defineInRange("edge_ppm", 30_000, 0, 500_000);
            mineMaxMultiplier = b
                    .comment("Largest multiplier the table pays. The board ends by itself once it is reached.")
                    .defineInRange("max_multiplier", 250, 1, 2_000);
            mineMaxPayoutChips = b
                    .comment("Largest win, in chips, one board may pay. The bet may be anything the card holds",
                            "as long as its first tile stays under this; a board stops by itself, cashed out,",
                            "when the next tile would pay more.")
                    .defineInRange("max_payout_chips", 250_000, 1, 1_000_000_000);
            mineDefaultMines = b
                    .comment("How many mines a fresh table starts with. Players choose 1 to 24.")
                    .defineInRange("default_mines", 3, 1, 24);
            mineActionSeconds = b
                    .comment("Seconds without a click before the server cashes the player out.")
                    .defineInRange("action_seconds", 120, 10, 1800);
            b.pop();

            b.comment("Slot machine").push("slot_machine");
            slotSpinTicks = b
                    .comment("Ticks before the first reel stops.")
                    .defineInRange("spin_ticks", 60, 10, 600);
            slotReelStagger = b
                    .comment("Ticks between one reel stopping and the next.")
                    .defineInRange("reel_stagger", 16, 0, 200);
            slotMaxStake = b
                    .comment("Largest stack the machine accepts.",
                            "The top prize is 800x, so this is what stops one pull from minting",
                            "eight hundred stacks of whatever was wagered.")
                    .defineInRange("max_stake", 16, 1, 64);
            slotMaxStakeChips = b
                    .comment("Largest bet in chips. At the 800x top prize, 320 chips is a 256000-chip win.")
                    .defineInRange("max_stake_chips", 320, 1, 1_000_000_000);
            b.pop();

            b.comment("Coin Flip duels").push("coin_flip");
            coinFlipTolerancePpm = b
                    .comment("How far apart two stakes may be valued and still duel.",
                            "100000 = 10%. Exact equality is unreachable with whole items.")
                    .defineInRange("stake_tolerance_ppm", 100_000, 0, 900_000);
            coinFlipTicks = b.defineInRange("flip_ticks", 100, 10, 600);
            b.pop();

            b.comment("Blackjack").push("blackjack");
            blackjackDecks = b
                    .comment("Shoe size. The shoe is reshuffled every hand, so counting is pointless",
                             "regardless; this only affects the odds very slightly.")
                    .defineInRange("decks", 1, 1, 8);
            dealerHitsSoft17 = b.define("dealer_hits_soft_17", false);
            allowDouble = b.define("allow_double", true);
            allowSurrender = b.define("allow_surrender", true);
            dealerPeek = b.define("dealer_peek", true);
            playerActionSeconds = b
                    .comment("Seconds the player has to act before the server forces a stand.")
                    .defineInRange("player_action_seconds", 60, 5, 600);
            b.pop();

            b.comment("Integrity and safety").push("safety");
            packetsPerSecond = b
                    .comment("Per-player casino packet budget. Sustained overflow disconnects.")
                    .defineInRange("packets_per_second", 20, 1, 200);
            abandonSeconds = b
                    .comment("Abort and refund a session whose owner has not touched it for this long.")
                    .defineInRange("abandon_seconds", 300, 30, 7200);
            protectBlockDuringWager = b
                    .comment("Cancel attempts by a player to break a table while a wager is in flight.",
                             "When false the table can be broken: a decided wager is settled first and every",
                             "item goes back to its owner, as it does for explosions and other removals.")
                    .define("protect_block_during_wager", true);
            logSettlements = b
                    .comment("Log every settlement at INFO. Keep this on: it is the only answer to",
                             "'the casino robbed me', and it is how you find the dupe you missed.")
                    .define("log_settlements", true);
            disabledGames = b
                    .comment("Games switched off on this server. Their tables still open, so nobody loses",
                             "what is in a slot, but refuse new wagers. Any of: upgrader, dice, blackjack,",
                             "coin_flip, slot_machine, vault, mine_field, cashier.")
                    .defineListAllowEmpty("disabled_games", List.of(), () -> "slot_machine",
                            o -> o instanceof String s && GAME_IDS.contains(s));

            b.pop();
        }
    }

    public static final class Common {
        public final ModConfigSpec.BooleanValue reportDerivationCycles;

        private Common(ModConfigSpec.Builder b) {
            reportDerivationCycles = b
                    .comment("Warn in the log when the recipe graph contains value-creating cycles.")
                    .define("report_derivation_cycles", true);
        }
    }
}
