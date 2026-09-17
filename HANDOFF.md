# Item Casino — handoff

**Read this whole file before touching anything.** It is the entry point for a fresh session and
describes the tree as it stands on **2026-09-16, late evening**. `README.md` is the short public
overview; this file is how the project is actually worked on.

* Project: `<project folder>` — Minecraft **1.21.11**, NeoForge **21.11.42**,
  Java **21**, Gradle **9.2.1**, ModDevGradle **2.0.141**, Parchment `2025.12.20`.
* Mod id `itemcasino`, root package `com.itemcasino`, version `0.1.0`.
* 162 Java files in `src/main` (200 classes), 14 in `src/test`, 22 registered game tests.
* **Languages.** Rémi writes in French: **answer him in French.** The **mod itself is English only**
  (his request): `en_us.json` is the only language file loaded. Code, comments, commit-style notes and
  this file stay in English.

---

## 0. Starting a fresh session — do this first

1. Read this file to the end.
2. Check the link to Rémi's machine: `device_bash` → `ls $HOME/mnt`. You need `itemcasino`. For the
   offline compile you also need the Gradle cache: if `files-2.1` is not listed, ask for folder access
   to `%USERPROFILE%\.gradle\caches\modules-2\files-2.1` (one prompt).
3. Where did the last run end? `device_bash`:
   `cd $HOME/mnt/itemcasino && cat build-status.txt gametest-status.txt && grep -a "required tests" gametest-out.txt | tail -1`
   and `ls run/crash-reports | tail -3`.
4. Bring the tree and the jars into the sandbox (§2.3): `bash $HOME/mnt/itemcasino/tools/offline/pack-inputs.sh`
   on the device, stage `Claude outputs/offline-src.tgz` and `Claude outputs/offline-jars.tar`, then in
   the sandbox: `mkdir -p ~/ic && cd ~/ic && tar xzf <src.tgz> && bash tools/offline/setup.sh <jars.tar>`
   and `bash tools/offline/check.sh`. Expect **ALL CHECKS PASSED** (200 classes, 6 `[removal]`
   warnings, JUnit 78/78, CoreSelfTest 158/158, 0 overrides, 0 static problems).
5. Compare `bash tools/offline/tree-hash.sh` in the sandbox with the same script on the device. Equal
   means you are working on exactly what is on his disk.
6. Then ask Rémi what he wants, or propose the top of §9.

---

## 1. The standing mandate

From Rémi's first brief, never relaxed:

1. **Act as a principal NeoForge developer.** Not a tutorial follower.
2. **Research before writing.** 1.21.11 renamed a great deal and remembered Forge APIs are wrong often
   enough to be worthless. `javap` and `unzip -p` on the real merged and sources jars answer any API
   question in one call (§5.2). Use them rather than guessing. Every entry in §7 cost a round trip.
3. **100 % server-authoritative.** The client never decides anything and never holds authority over
   an item or a chip. Outcomes are decided once, at commit, and the client replays them.
4. **Verify offline everything that can be** before asking him to build: *"each round trip costs me two
   minutes."* Ask for exactly **one** `.bat` per round trip.
5. **Modern APIs only.** No deprecated path where a current one exists.
6. **Computer use was offered and declined.** Do not drive Explorer or PowerShell, do not ask again.
7. For real design choices, use `AskUserQuestion` with 2–4 options and put your recommendation first,
   marked "(Recommandé)". He answered every such question quickly and usually took the recommendation.

---

## 2. How work actually gets done

### 2.1 The two machines

* **The cloud sandbox** (Bash, Read/Write/Edit): has `javac`, Python, git. This is where code is
  written and compiled. It cannot reach Maven or NeoForge's maven, so jars come from Rémi's disk.
* **Rémi's machine** through `device_bash`: a Linux VM with the project mounted at
  `$HOME/mnt/itemcasino` (and the Gradle cache at `$HOME/mnt/files-2.1` when granted). It has `java`,
  `python3`, `git` — **no `javac`, no Gradle**. Good for reading, grepping logs, `mv`, and packing
  tarballs. The project folder is **not** a git repository. Deleting there needs
  `device_request_delete_permission`; prefer `mv -n` into an archive folder.

### 2.2 Building and running: Rémi double-clicks

| Script | Runs | Writes | Status file |
|---|---|---|---|
| `run-build.bat` | `gradle build` (includes JUnit) | `build-errors.txt` | `build-status.txt` → `DONE 0` |
| `run-gametest.bat` | `gradle build runGameTestServer` | `gametest-out.txt` (~5 MB) | `gametest-status.txt` |
| `run-client.bat` | `gradle runClient` | — (console stays open) | — |

All three call `%USERPROFILE%\gradle-dist\gradle-9.2.1\bin\gradle.bat`. `gametest-out.txt` is big: grep
it with `device_bash` (`grep -a "required tests"`, `grep -a "itemcasino gametest:"` for a failure
message), never read it whole. Crashes land in `run\crash-reports\`, the live log is
`run\logs\latest.log`; read **both** (§8.1 is a crash report that lied).

The loop: **edit in the sandbox → `check.sh` → commit to the device → ask for one `.bat` → read the
result with `device_bash`.**

### 2.3 Moving files

* **Into the sandbox:** `tools/offline/pack-inputs.sh` (run with `device_bash`) writes
  `Claude outputs/offline-src.tgz` (the project) and `Claude outputs/offline-jars.tar` (the 37
  jars of `tools/offline-classpath.txt` from the Gradle cache + the NeoForge merged and sources jars, ~85 MB). `--src-only` skips the
  jars when the tar is already there. Stage the two files with `device_stage_files`
  (`<project folder>\Claude outputs\...`); they appear under
  `/mnt/user-data/uploads/itemcasino/Claude outputs/`.
  Staging limits: files more than **7 folders deep** are refused (the tarball avoids that), 50 files
  and 500 MB per call.
* **Back to the device:** copy each changed file to `/mnt/user-data/outputs/commit/<same path>`, then
  `device_commit_files` with `stagedPath` → `<project folder>\<path with \>`, **≤ 50
  files per call**. `setup.sh` makes the sandbox tree a git repo, so `git status --short` is the list.
  **Before committing, check the device copy has not changed** (Rémi may have edited it): compare
  `md5sum` of the files you are about to overwrite with `git show HEAD:<path> | md5sum`, or compare
  `tree-hash.sh` on both sides. After committing, compare `tree-hash.sh` again.
  The commit tool re-encodes PNGs (same pixels, different bytes), which is why the hash skips them.
* A moved or deleted file is not a commit: do it with `device_bash` (`mv -n`), mirroring `git mv`.

---
## 3. What the mod is today

Everything below has been through Gradle and the game tests (§4), and all of it except the items
marked *(not yet seen in game)* has been played by Rémi in `runClient`.

### 3.1 The tables

| Block (id) | Recipe | Stake | Return to player | What it does |
|---|---|---|---|---|
| Upgrader (`upgrader`) | 4 diamonds, 4 gold, anvil | items or chips | 90 % | Pick a target item; a wheel spins at `0.9 × stake / target` odds; win pays the target. Shots longer than 1 in 1000 are refused. |
| Predict the Dice (`predict_the_dice`) | redstone blocks, gold, ender eye | **chips only** | 97 % (`dice.edge_ppm`) | Roll 0.00–99.99; pick a chance 1–95 % and under/over. Win cap `dice.max_payout_chips` 250 000, so long shots allow a smaller bet. Old Double or Nothing ids are aliased to it. |
| Blackjack (`blackjack_table`) | green wool, gold, book, planks | items or chips | ~99.4 % basic strategy | One deck, S17, double, late surrender, peek, 3:2. Double takes a second identical stack, or twice the chips on the same card. |
| Coin Flip (`coin_flip`) | iron blocks, gold, ender eye | items or chips (both players the same kind) | 100 % (PvP) | Two chairs, stakes within 10 % of each other, winner takes both. |
| Slot Machine (`slot_machine`) | quartz block, ender eye, redstone block, iron, gold | items (≤ 16) or chips (≤ 320) | 90.0 % (enumerated) | Three reels, triples up to ×800, pairs from Iron up. Rules in the (i). |
| Mine Field (`mine_field`) | TNT, pressure plate, iron, gold, redstone | **chips only** | 97 % whatever the strategy | 5×5, 1–24 mines, multiplier cap ×250, win ceiling `mine_field.max_payout_chips` 250 000: the stake may be anything whose first tile fits, and a board cashes itself out when the next tile would pass the ceiling. Never forfeits on timeout/restart: cashes out. |
| The Vault (`vault`) | nether star, gold blocks, obsidian | items or chips | 80 % of the offering | Choose a share of the shared pot (1–100 %); odds priced so a draw is worth 80 % of the offering whatever the share; 50 % ceiling per draw; only 50 % of the offering goes into the pot (`jackpot.draw_pot_share_ppm`), the rest is destroyed. |
| Cashier (`cashier`) | gold, emerald, iron around a chest | — | 98 % round trip | Items → chips at full value; chips → up to 8 currencies at value + 2 % (`chips.withdraw_fee_ppm`, `chips.currencies`). |

### 3.2 Chips and the chip card

* 1 chip = 1 value point. A **Chip Card** (`chip_card`, stack size 1) stores its balance in hundredths
  of a chip in the data component `itemcasino:chips`. It is **bearer**: whoever holds it owns the
  chips. It is never valued as an item (`StackValuator` returns `INF`). No recipe: the Cashier issues
  one on the first deposit; a card put in the deposit slot merges into the one in the card slot.
* A chip bet: the card sits in the wager slot, the bet is a per-table setting. The **bet column** is a
  vertical panel to the **left of the table window** (½, −, +, Max, logarithmic drag track, wheel,
  card balance). On commit the whole card goes to escrow; on settle the new balance is written and the
  card goes straight back into the slot.
* Chips-only tables show a faded card in the empty slot with a hover hint; the Cashier's card slot does
  the same *(Cashier hint not yet seen in game)*.

### 3.3 The shared jackpot

Every loss at a house table is banked server-wide (`jackpot/Jackpot`, `SavedData`: items up to 128
kinds, plus chips). Two ways out: the Vault, and an ambient 20 ppm chance on any wager (see §9, item 2:
its threshold is buggy). Wins are announced in chat, in chips.

### 3.4 Other content

* **Pocket devices** (`pocket_upgrader`, `pocket_dice`, `pocket_blackjack`): an item hosting the same
  session in memory; settled and liquidated when the screen closes.
* **Gilded Gil** (`gambler_goblin`): throw a gold block or 8 ingots on the ground and he appears.
  Right-click: blackjack. Sneak + right-click: dice (so chips only). Leaves after 5 minutes idle;
  everything he holds is settled on every exit path.
* **Valuation engine**: walks the loaded recipe graph at datapack sync and prices every item from
  `data_maps` base values; items nothing reaches get a low rarity estimate and are never upgrade
  targets. `/casino value|odds|diagnose|dump|rebuild`.
* **Stats** per player (`$` button in the survival inventory) and a **mailbox** for anything that
  cannot be handed over now (full inventory, offline, disconnecting).

### 3.5 Presentation rules that are now load-bearing

* **Nothing about a result reaches the player before the animation that shows it.** Wheels, reels,
  coin and dice replay a decided outcome; the payout packet waits for the acknowledgement or the
  deadline. Blackjack deals card by card (`core/game/blackjack/DealClock`, shared by client and
  server) and the server holds the settle until the client has shown the last card.
* **Payouts hand themselves over** at the settle (inventory, then mailbox). Collect only exists for a
  payout parked by a restart or an abandoned table. After a result, the commit button is dead for
  24 ticks so a stray click does not start the next game.
* **Win banner** (`client/render/WinBanner`): gold, animated, three tiers (win, big ≥ ×10, jackpot),
  shown only to the winner.
* **Every table's (i) is in the top-right corner** of the window (`AbstractCasinoScreen.infoText()`).
* Every screen's geometry comes from `menu/CasinoLayout`; never hard-code a coordinate.

---

## 4. Verification status and recent history

**Last Gradle run: `run-gametest.bat` on 2026-09-16 at 22:59 — build OK, "All 23 required tests passed"**
(Gradle counts 23 in the batch for our 22 registered functions; the extra one was not investigated).
That tree included everything up to the automatic payout delivery. Changed since, verified offline
only: the Cashier card placeholder (client screen + one lang key), English-only (the French file moved
out of the resources), `tools/offline/`, and these documents. **Nothing since touches server logic.**

**Not yet seen in game by Rémi:** automatic payout delivery and the 24-tick button lock, the (i) badges
in the top-right corner, the Vault banking half, the chip-card hints (tables and Cashier), the game in
English on a French client.

History, newest first (the details live in the code comments and in §8):

* **09-16 late** — English only; Cashier card placeholder; offline toolkit moved into `tools/offline`;
  documentation rewritten.
* **09-16 night** — payouts delivered at settle (no second banner from Collect), button lock, all (i)
  top right, Vault banks 50 % of offerings, chip-card hints. Game tests 23/23.
* **09-16 evening** — results wait for their animation on every table; win banner; blackjack paced by
  `DealClock` and its payout held until the reveal is shown; slot machine names dead pairs; slot rules
  moved into an (i). Network version 6. Game tests 23/23.
* **09-16 afternoon** — chips: card, Cashier, bet column, chips-only Dice and Mine Field, chip duels,
  chips in the pot; Mine Field win ceiling. Game tests 23/23.
* **09-16 morning** — Predict the Dice replaced Double or Nothing (aliases for old worlds); Vault
  share slider and hold-to-repeat steppers; player-perspective audit (`AUDIT-JOUEUR-2026-09-16.md`).
* **09-15** — Mine Field; Vault share of the pot; the audit fixes (duel pot theft, table breaking,
  blackjack restart refund, Upgrader long shots, slot payout packets, estimated item values, stats,
  mailbox).
* **Before** — M1–M5 of the original blueprint: Upgrader, Double or Nothing, Blackjack, Coin Flip,
  Slot Machine, Vault, pocket devices, goblin, valuation engine, sounds, textures.

---

## 5. The offline toolkit — `tools/offline/`

Everything here runs in the sandbox after `setup.sh`; together it is `check.sh` (about 25 s).

| Script | What it does |
|---|---|
| `pack-inputs.sh` | **Device side.** Tars the project and the classpath jars into `Claude outputs/`. |
| `setup.sh <jars.tar>` | Extracts the jars into `.offline/jars`, writes `.offline/cp.txt`, `junit-cp.txt`, `merged-jar.txt`, and makes the tree a git repo with a baseline commit (`.offline/` is excluded). |
| `compile.sh` | `javac` of all of `src/main/java` against the real classpath. Clean = 200 classes, 6 `[removal]` warnings (`makeMockServerPlayerInLevel` ×3, `Items.registerItem(…, Properties)` ×3). `-sourcepath ""` and `-implicit:none` are required: the merged jar also contains `.java` files. |
| `junit.sh` | Compiles `core/**` + `src/test/java` and runs them with `RunJUnit.java` (JUnit Platform launcher). 78 tests. |
| `static_checks.py` | JSON parses; every translation key named in Java exists in `en_us.json`; every block/item has a name; game-test functions ↔ `test_instance` JSONs pair up both ways (a function without its JSON silently never runs); no `net.minecraft.client` import outside `com.itemcasino.client`. |
| `check.sh` | All of the above + `tools/CoreSelfTest.java` (158 assertions) + `tools/audit_overrides.py`. |
| `tree-hash.sh [dir]` | One md5 over the text files, to compare sandbox and device. |
| `text_width.py "text" …` | Estimated pixel width in the default font (±10 %), to check a label fits. |

### 5.1 The pure core

`com.itemcasino.core.**` has **zero Minecraft imports**: fixed-point values, the recipe solver, odds,
wheel maths, blackjack rules and `DealClock`, slots, dice, mine field, Vault odds, chips arithmetic and
the session state machine. **Anything with a multiplier, a probability, a rounding rule or a state
transition belongs there**, with a JUnit test, so it is proved offline rather than guessed in game.

### 5.2 The jars are an oracle

After `setup.sh`: `javap -cp "$(cat .offline/merged-jar.txt)" net.minecraft.some.Class` gives exact
signatures; `unzip -p .offline/jars/build/moddev/artifacts/neoforge-21.11.42-sources.jar
net/minecraft/…/Foo.java` gives vanilla and NeoForge source. Prefer this to the web: it is the exact
version being compiled.

### 5.3 `tools/audit_overrides.py`

Finds mod methods that override a vanilla method, narrow its return type, and get the narrower type by
casting — the shape that crashed the client in §8.1 and that the compiler accepts silently. Run it after
touching anything that extends a vanilla class (`check.sh` does).

---

## 6. Architecture

### The spine

```
SessionHost (interface)             ← a block entity, a pocket item, or the goblin
   └── CasinoSession (abstract)     ← escrow, state machine, chips, payout, reveal, jackpot banking
          ├── UpgraderSession    DiceSession        BlackjackSession
          ├── CoinFlipSession    SlotMachineSession VaultSession
          └── MineFieldSession
CashierMenu                         ← no session: a counter with two slots, priced by the server
```

`SessionHost` answers only what a session cannot: where am I, who is watching, where do leftovers go,
how do I persist. Implementers: `block/AbstractCasinoBlockEntity` (persists with ValueIO),
`session/PocketCasino.Host` (memory), `entity/GamblerGoblin` (memory, settles on every exit).

### The rules that keep the ledger honest

1. **Decide at commit, replay as animation.** The outcome is rolled once when the wager locks and saved.
2. **`SETTLING` is a one-way door:** `IDLE → ARMED → LOCKED → ROLLING → SETTLING → PAYOUT_PENDING → IDLE`
   (`core/game/SessionMachine`, tested). A replayed settle cannot pay twice.
3. **A restore repairs forward:** a session saved mid-game materialises what was already decided
   (blackjack rebuilds the hand from seed + actions and stands; the mine field cashes out).

### The settle, end to end

`placeWager` → `checkStake` (card or items, chips-only refusal) → `escrowStake` (whole card or stack) →
decide → `S2C…Result` → client animates → `C2SAnimationComplete` (accepted only after ¾ of the
animation on the server clock) or the deadline → `settle()` → `materialiseDecidedOutcome()` (items into
`payout`, or `payChips` writing the card) → stats → `bankLoss()` → `returnCardsToSlots()` →
`broadcastPayout()` (`S2CPayoutReady` with `winnerSeat`, `tier`, `winCents`) →
`handOverSettledPayout()` (inventory, then mailbox). The client queues the result as a `Reveal`, shows
it when no animation is running (`revealReady()`), plays the sound, shows the banner to the winner.
Blackjack adds a reveal phase: `beginReveal()` sends `S2CBlackjackSettled` with both final hands,
keeps the session ROLLING for `DealClock.revealTicks(…)`, and `finishReveal()` settles.
Vault: the share is taken out of the pot at commit (`Jackpot.takeShare`) and delivered and announced
at the settle (`Jackpot.deliver`).

### The menu data channel

`AbstractCasinoBlockEntity.DATA_*`, 18 ints, per viewer: state, odds, has-payout, spin ticks, can-act,
seat, option, stake A/B (milli-points), reels, six game read-outs `AUX_A..F` (each session's
`readout(i)`), bet, max bet. Values are ints: vanilla sends shorts, NeoForge upgrades out-of-range
values to a VarInt payload (§7), longs do not fit. Hidden information (mine layout, a roll in flight)
reads 0 until it is public.

### Other landmarks

* `menu/CasinoLayout` — **the single source of truth for GUI geometry.** `WIDTH 216`, `HEIGHT 228`,
  `ROW_Y 108`, `WAGER_X 20`, `INV_X 27`, … Both the menu's slot positions and
  `client/render/CasinoPanel`'s background drawing read from it. Rémi reported slots that did not
  line up with what was drawn; this is the fix, and it only stays fixed if nothing hard-codes a
  coordinate.
* `valuation/` — `RecipeHarvester` walks the recipe graph, `core/value/Relaxer` solves it.
  Mod items are included: it walks whatever recipes are loaded, not a vanilla list.
* **Ownership.** A seat is a UUID and owns its slot. `SessionHost.seatId(i)` answers "whose" (works
  offline); `seatedPlayer(i)` answers "who can I send a packet to" (null once the screen closes) and
  must never decide ownership. `CasinoSession.wagerOwner` is captured at commit; `payoutOwner()`
  decides who may claim. Closing a table with nothing committed releases the seat *and returns the
  stake*; `AbstractCasinoBlockEntity.sweepAbsentSeats` (every 20 ticks) cleans up after crashes and
  mails abandoned wins; `preRemoveSideEffects` handles every kind of block removal (settle first,
  then deliver to owners).
* `player/CasinoMailbox` — `SavedData`: items owed to a player who cannot take them now
  (disconnecting, dead, inventory full, offline). Delivered on login, respawn, opening any table, and
  from the stats screen. **Never put items in a disconnecting player's inventory**: `PlayerList.remove`
  saves the player *before* the container is closed, so they are lost.
* `player/CasinoStats` — `SavedData` keyed by UUID, written at settle time (a hand can settle after
  logout). Shown by `client/screen/StatsScreen`, opened from the `$` button that
  `client/InventoryStatsButton` adds to the survival inventory.
* `core/value/EstimatedValues` — *known* values come from base values through recipes; items nothing
  reaches get a low rarity *estimate* (`estimate_common` = 1…), carried forward through recipes.
  Estimated items can be wagered but are never targetable. See §8.7.
* `jackpot/Jackpot` — `SavedData` on the overworld, `SavedDataType<T>(String, Supplier<T>, Codec<T>)`,
  128-entry cap.
* `network/` — `c2s/`, `s2c/`, `handler/ServerHandlers`, plus `RateLimiter`.
* `core/game/jackpot/JackpotOdds` — holds the property `chance × pot = return × offered`, so a draw
  returns a fixed 80 % whatever the pot currently holds. The Vault itself now uses
  `core/game/VaultOdds`, the same property against the chosen share's real prize.
* `core/game/MineField` — the mine field's multipliers, layout and finish rule.
* `client/render/InfoBadge` — the (i) rules tooltip, plus `percent()`/`times()` formatting (separator
  from the `itemcasino.decimal_separator` key). `client/render/ChipText` formats chip amounts.
* `chips/ChipCards` (card ↔ balance), `core/chips/Chips` (all chip arithmetic: floors on payouts,
  ceiling on withdrawal cost), `registry/CasinoDataComponents` (`itemcasino:chips`).
* `client/render/ValueStepper` — hold-to-repeat, wheel and drag for any number a screen sets, sending
  at most every 3 ticks.
* `network/CasinoNetwork.VERSION = "6"` — bump it whenever a payload changes shape.

---

## 7. 1.21.11 API facts, learned the hard way

Each of these cost a round trip. Do not re-derive them.

* `ResourceLocation` is now **`net.minecraft.resources.Identifier`**.
* `Entity.moveTo(...)` is now **`snapTo(...)`**. The whole family was renamed; the old name does not
  exist.
* `AbstractButton.renderWidget` is **`final`**. Override
  `renderContents(GuiGraphics, int, int, float)`.
* `GuiEventListener.mouseReleased(MouseButtonEvent)` takes **one** argument;
  `mouseDragged(MouseButtonEvent, double, double)` takes three.
* `@EventBusSubscriber` has **no `bus` element**. Mod-bus listeners must be registered explicitly:
  `modBus.addListener(ItemCasino::onEntityAttributes)`.
* Block entity persistence is `ValueInput` / `ValueOutput` (ValueIO), not `CompoundTag`.
* `SavedData` is reached via
  `level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE)`.
* Networking: `RegisterPayloadHandlersEvent`, `playToClient(TYPE, CODEC)` (**2 arguments**),
  `RegisterClientPayloadHandlersEvent`, `ClientPacketDistributor.sendToServer`.
* `EntityType.Builder.build(ResourceKey<EntityType<?>>)`; `EntityType.create(Level, EntitySpawnReason)`.
* Entity rendering uses render states:
  `HumanoidMobRenderer<T extends Mob, S extends HumanoidRenderState, M extends HumanoidModel<S>>`
  with `createRenderState()` and `getTextureLocation(S)`.
* `pack.mcmeta`: since 1.21.9, `pack_format` is replaced by **`min_format` / `max_format`**, each an
  int or `[major, minor]`. For 1.21.11 the value is `[94, 1]` — taken from NeoForge's own
  `pack.mcmeta` inside the merged jar, which is more reliable than any wiki table.
* Block entities expose `onViewerClosed(Player)` in this codebase — check `@Override` actually
  applies when renaming anything on that interface.
* **Block removal**: `BlockEntity.preRemoveSideEffects(BlockPos, BlockState)` is called by
  `LevelChunk.setBlockState` for *every* removal (player, explosion, `/setblock`, other mods). The
  default only drops contents for a `Container`. `BlockEvent.BreakEvent` is players only.
* `requiresCorrectToolForDrops()` without a `minecraft:mineable/<tool>` block tag means **no tool is
  ever correct**: no drops, 1/100 break speed. The tag lives in
  `data/minecraft/tags/block/mineable/pickaxe.json`.
* `ByteBufCodecs.list(max)` **throws `EncoderException` on encode** past `max`, and
  `ClientboundCustomPayloadPacket` is not skippable, so an oversized list disconnects the receiver.
* Toss: `ItemTossEvent` fires for Q and for dragging out of a screen (`Player.drop(ItemStack, boolean)`),
  and cancelling it destroys the stack. `EntityJoinLevelEvent` fires for *every* item entity,
  including mined blocks, death drops and chunk loads (`loadedFromDisk()`).
* Disconnect order: `player.disconnect()` (sets `hasDisconnected()`), then `PlayerList.remove`:
  `PlayerLoggedOutEvent` → **save** → `removePlayerImmediately` → container closed.
* Vanilla gives chests, furnaces, shelves and decorated pots an **empty** `DataComponents.CONTAINER`
  by default: test for contents, not for the component.
* `Screen.renderBackground` blurs unless `isInGameUi()` returns true. `ScreenEvent.Init.Post#addListener`
  adds a renderable widget to any screen; `AbstractContainerScreen#getGuiLeft/getGuiTop` exist.
* Data maps: an unknown item id logs an error and is skipped; an unknown tag resolves to empty. For a
  key covered twice, the later entry wins.
* **Data components**: `DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID)`,
  then `registerComponentType(name, b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG))`.
* `Items.registerItem(String, Function, Properties)` is **deprecated for removal**; use the
  `UnaryOperator<Properties>` overload (the chip card does; the three pocket items still do not).
* `DeferredRegister.addAlias(from, to)` makes an old registry id load as a new one (blocks, items and
  block-entity types each need their own alias). Used for Double or Nothing → Predict the Dice.
* `Item.appendHoverText` is deprecated: add tooltip lines with NeoForge's `ItemTooltipEvent`
  (`getItemStack()`, `getToolTip()`), client side.
* One `@EventBusSubscriber(value = Dist.CLIENT)` class can hold both mod-bus and game-bus handlers:
  each event is routed by its type (`ItemCasinoClient` registers the menu screens, a mod-bus event, next
  to game-bus handlers, and the screens do open in game).
* **Container data is sent as shorts** by vanilla (`ClientboundContainerSetDataPacket.writeShort`).
  NeoForge sends `AdvancedContainerSetDataPayload` (VarInt) instead when the value does not fit and the
  client has the channel, so ints are safe, longs are not.
* GUI rendering is deferred into strata: `GuiGraphics.nextStratum()` puts what follows above the slots
  and their items (the win banner). `pose()` is a 2-D `Matrix3x2fStack`
  (`pushMatrix/translate/scale/popMatrix`). Tooltips: `setTooltipForNextFrame(font, lines, x, y)` and
  `setComponentTooltipForNextFrame`.
* `AbstractContainerScreen.hasClickedOutside(double, double, int, int)` decides whether a click throws
  the carried stack. Anything drawn outside `imageWidth × imageHeight` that takes clicks (the bet
  column) must be excluded there. `mouseClicked(MouseButtonEvent, boolean doubleClick)`,
  `mouseScrolled(x, y, scrollX, scrollY)`, `Minecraft.getInstance().hasShiftDown()`,
  `mouseHandler.isLeftPressed()`.
* `ServerPlayer.openMenu(provider, buf -> …)` plus a menu type created from
  `IMenuTypeExtension.create((id, inv, buf) -> …)` passes data to the client constructor (the Cashier
  sends its rates that way). `FriendlyByteBuf.writeIdentifier/readIdentifier`.
* ValueIO: `ValueOutput.store(key, codec, value)`, `ValueInput.read(key, codec)`, `getLongOr/getIntOr`.
  Outside ValueIO, `CompoundTag.store(key, codec, registryAccess().createSerializationContext(NbtOps.INSTANCE), value)`
  for anything with components (item stacks).
* `GameTestHelper.makeMockServerPlayerInLevel()` returns a player hard-coded to **creative** (and is
  deprecated for removal): tests set `getAbilities().instabuild = false`.

---

## 8. Bugs fixed, and the lesson each one carries

### 8.1 The goblin crashed the client — an accidental covariant override

`SessionHost` declared `ServerLevel level()`. `GamblerGoblin` implemented it as
`return (ServerLevel) super.level()`. But `Entity` already has `level()` returning `Level`, so Java
accepted this as a perfectly legal covariant override — no warning. Every *vanilla* call to
`goblin.level()` then ran that cast, and `EntityRenderer.extractRenderState` does
`Level level = entity.level()` on a client where the level is a `ClientLevel`.

The cruel part: `EntityRenderDispatcher.extractEntity` catches the throwable and calls
`entity.fillCrashReportCategory(...)` to describe the failing entity — which calls `level()` again and
throws again. **The crash report destroyed itself while being written**, which is why it had no
"Entity being extracted" section and named a `ClassCastException` with no apparent cause.

Fixed by renaming the interface method to **`hostLevel()`** across all implementers. A host is only
ever asked for its level on the server, so the interface takes a name vanilla will never collide with.
`tools/audit_overrides.py` exists to catch the next one.

**Lesson:** when a mod interface and a vanilla superclass can both claim a method name, the vanilla
one wins and the mod one becomes a landmine. Check names against the real class hierarchy.

### 8.2 The wheel showed the mirror of the decision

The renderer places sector `from` at screen angle `rotation + from`, so the sector under a fixed
pointer is at `−rotation`. Spinning by `+angle` displayed the wrong sector on **71 %** of spins —
verified offline, not guessed. Fixed with `WheelMath.drawRotation(angle) = −angle` and
`underPointer(rotation) = normalise(−rotation)`, plus a 200 000-trial round-trip test.

**Lesson:** a sign error in a display transform is invisible in play and obvious in a loop.

### 8.3 Winning a 64-stack paid one stack instead of two

`Inventory#add` returns "did I place *any* of it" while mutating the stack it was given. The boolean
was being used as "did it all fit", so partial remainders were silently dropped. Same bug in
`liquidate`. Fixed by copying, adding, and keeping whatever is left in `leftovers`. Two regression
game tests: `win_pays_double`, `payout_survives_full_inventory`.

**Lesson:** read the actual contract of any vanilla method that both returns and mutates.

### 8.4 The Vault always said "1 in 20"

Not a display bug. The 5 % per-draw ceiling was binding, so extra offering bought nothing and the
80 %-return property quietly broke. Fixed with `JackpotOdds.maxUsefulOffer` =
`pot × max / (return − max)` — the point where the ceiling first bites — plus `usefulPart()` /
`usefulCount()` and partial escrow, so the Vault only takes what it can actually pay for.

### 8.5 The slot machine, twice wrong

First pass: coal pairs paid nothing — *"deux charbons ne compte pas ?"*. Second pass: every pair paid
×1, so 65 % of spins returned something and *"on perd jamais"*. Final paytable pays pairs from Iron
up; RTP **0.900002** by enumerating all 64³ = 262 144 outcomes, and **78.82 %** of spins lose
outright. Below Iron a pair is a near miss, and reads as one.

### 8.6 Game tests failing for environmental reasons

* *"Payload itemcasino:value_table may not be sent to the client!"* — `CasinoNetwork.send` now checks
  `player.connection.hasChannel(payload)`. The real bug it hid: a fake player from another mod could
  take down the server tick.
* *"the wager was refused"* — `makeMockServerPlayerInLevel()` hard-codes `GameType.CREATIVE`; the test
  now sets `instabuild = false`.
* A `test_instance` JSON missing `"environment": "minecraft:default"` fails to parse, and a mod's
  built-in datapack that fails to parse **stops the world loading at all**. `build.gradle` carries a
  comment about this.
* `spectators_cannot_act` was registered as a test function but had **no `test_instance` JSON**, so it
  had never run once — the suite reported "All 7 required tests passed" and looked healthy. A test
  needs *both* halves in this framework, and the missing half is silent. There are now 13 registered
  functions and 13 instances, cross-checked by name. It also passed for the wrong reason until
  2026-09-15: its onlooker was in creative mode, so the creative guard refused the wager before the
  seat check was ever reached. Re-check that pairing whenever a
  test is added.

### 8.7 Items nothing can price were worth more than iron

The rarity fallback gave every common unreachable item 16 points (raw iron is 12): netherrack,
cobbled deepslate, seeds, rotten flesh, snowballs. `EstimatedValues` now separates known from
estimated values, estimates are low (1 / 8 / 32 / 128 by rarity, renamed config keys so existing
worlds pick up the new defaults), carried forward through recipes (blue ice = 81 ice, not 1), and
never targetable, so a wrong guess can only cost the player who wagers the item. `base_value.json`
gained ~80 vanilla raw materials and `c:` tags so most common items are *known*.

### 8.8 The loser of a duel could collect the pot

`winnerId` came from `seatedPlayer(seat)`, null when that player had closed the screen, and a null
winner let any seated player claim. Now from the seat's UUID; a duel with no named winner pays nobody.
Regression: `duel_pays_only_the_winner` (mock players never have a menu open — the exact case).

### 8.9 Breaking a table in survival

No `mineable/pickaxe` tag (§7), so no drops. Slot contents vanished on any break, and non-player
removals destroyed escrow and payouts. `preRemoveSideEffects` now settles and delivers to owners.
Settling first also closes "blow up the table when the result packet says loss". The old test called
`spillEverything` directly; `breaking_spills_once` now destroys the block.

### 8.10 Blackjack restart refund, and a collateral dupe

A hand interrupted by a reload was refunded: quit on bad hands, keep good ones (+8.6 % per hand,
simulated). Hands are now persisted as seed + rules + actions (`core/game/SeededRoller`), rebuilt card
for card on load, and stood. While there: the base repair cleared `escrow` but not `doubleEscrow`,
so breaking the table afterwards returned the collateral a second time.

### 8.11 The Upgrader paid over the odds on long shots

`Odds.ppm` raised any chance below 0.1 % *up* to 0.1 %: 1 cobblestone against a dragon egg returned
50× on average. Below the floor is now `ILLEGAL`. JUnit and `CoreSelfTest` assert that no legal
wager returns more than 90 %. The acknowledgement packet is also ignored until ¾ of the animation
has elapsed on the server (`CasinoSession.acknowledgementDue`).

### 8.12 Big slot-machine wins

A triple star on 16 ender pearls is 800 stacks: the 64-entry payout packet threw in the encoder and
disconnected the winner and every spectator, and `addSplit` silently stopped at 512. The client now
receives a per-item summary (`PayoutResolver.summarise`), the split cap is 16 384 and logs, and the
jackpot hands out counts directly with the overflow going to the mailbox.

### 8.13 Results arrived before their animation

The Vault played a level-up sound and put the prize in the inventory and the chat at the *start* of a
winning draw; the blackjack totals were read from the packet, so the dealer's final total showed while
his cards were still in the shoe; the result line and the tint showed with the last card still
sliding. Fixed by a rule, not a patch per table: the result is queued client side and revealed only
when `revealReady()`; sounds belong to the reveal; blackjack totals are summed from landed cards; the
Vault takes the prize at commit but delivers and announces it at the settle.

**Lesson:** when a server decides early, every channel that leaks the decision (sound, chat, inventory,
data slots, totals) has to wait, not just the text.

### 8.14 Blackjack dealt the opening hand all at once

A new hand was detected by "the hand got shorter", which is false whenever the new hand has as many
cards as the old one, so the four opening cards appeared instantly. The dealing-order maths also put a
dealer draw before a player hit. Now a new hand is a new session id, and the order is an explicit queue
in `DealClock` (JUnit: `DealClockTest`, including that the server's estimate of the reveal equals what
the client takes).

### 8.15 Collect showed a second win banner

`deliverPayout` re-broadcast the payout packet, which the client took as a new result. Payouts are now
delivered at the settle and Collect no longer broadcasts; the commit button is locked for 24 ticks after
a result so the click aimed at the banner cannot deal the next hand.

### 8.16 "Three different" on a visible pair

A pair that pays nothing is `Kind.NOTHING` in the paytable (so it is not celebrated), and the screen
mapped `NOTHING` to "three different". The screen now looks at the faces and says "Pair of Coal — no pay".

### 8.17 A spectator could read a dice roll before it landed

The last-roll read-out on the data channel held the new roll as soon as it was decided. It now reads 0
while the wager is held, like the mine layout.

---

## 9. Open items, in the order worth doing them

1. **See the latest batches in game** (`run-client.bat`): a blackjack win (banner once, items straight
   into the inventory, no Collect, the Deal button dead for about a second); a Vault draw (the (i)
   top right says 50 % goes to the pot; a win is delivered and announced only when the dial stops);
   the empty card slot on the Dice, Mine Field and Cashier; the whole UI in English on a French client
   (long English strings are narrower than the French ones were, but check the Cashier and the
   blackjack buttons); a chip duel; a full inventory receiving a win (the rest goes to the mailbox).
2. **P0 — the ambient jackpot threshold compares points to micro-points.** `Jackpot.rollAmbient`
   compares `wagerValue` (micro-points, `Fixed.ONE = 1 000 000`) with `jackpot.ambient_min_wager_value`
   (50, meant as points), so any wager gets the 1-in-50 000 chance at the whole pot. Same unit mistake
   for the Vault's `draw_min_value`. Use `Fixed.ofPoints`. Then bound the ticket's expectation to a
   fraction of the stake (numbers in `AUDIT-JOUEUR-2026-09-16.md` §2.2: with a big pot the smallest bet
   is profitable, and the Mine Field, with no animation, farms it fastest).
3. **Blackjack after reopening the screen mid-hand**: the client state is reset on close and nothing
   re-sends the hand when the screen opens again, so the table looks empty until the action timer
   stands the hand (and during a reveal, until its deadline). Send the hand to a viewer in `openFor`
   (and `S2CBlackjackSettled` again if a reveal is in progress).
4. **Item tooltips for the blocks**: `itemcasino.tooltip.slot_edge`, `.vault`, `.mine_field`, `.dice`
   exist in `en_us.json` but nothing reads them (the (i) badges use the blackjack, coin flip and
   upgrader ones). Wire them through `ItemTooltipEvent` like the chip card. Unused keys to delete while
   there: `itemcasino.label.odds`, `.label.dealer_total`, `.label.player_total`,
   `.message.table_busy`, `.message.seat_taken`.
5. **The six `[removal]` warnings**: `Items.registerItem(…, Properties)` for the three pocket items
   (switch to the `UnaryOperator` overload) and `makeMockServerPlayerInLevel` in the game tests (find
   what 1.21.11 offers instead with `javap` on `GameTestHelper`).
6. **Slot machine item stakes** above 16 are refused rather than partly taken (audit §5).
7. Design questions never settled: should losses at the tables bank 100 % into the pot (today) or only
   part, now that the Vault destroys half of each offering? A **pocket slot machine** was offered, never
   asked for.
8. `tools/translations/fr_fr.json` is the last French translation, **not loaded and not maintained**;
   delete it if Rémi confirms English only is permanent.
9. `Claude outputs/` holds working files: the two offline tarballs (regenerate them, do not trust old
   ones) and `archive-2026-09-15/` (old audit, old handoff, simulations). Nothing there is loaded.

---

## 10. Working style Rémi responded well to

He gives several unrelated items in one message, often mixing a bug report, a question and a new
feature. Handle all of them, and answer the question rather than only fixing the bug — *"pourquoi
c'est 45 % ?"* deserved an explanation of the house edge, not just a change to 50 %.

He notices presentation. Interfaces, block textures and sound were all things he asked to have
*worked on* rather than merely made functional.

When something is wrong he describes the symptom, not the cause — *"on perd jamais"*, *"ça ne donne
qu'un stack"*. The cause has, every time so far, been somewhere other than where the symptom pointed.

He plays and reports in batches of four or five short remarks (*"au blackjack tout va vraiment trop
vite"*, *"le (i) est in the way"*). Take each literally, then look for the rule behind it: "the Vault
announces before the dial stops" turned out to be true of every table, and he had asked to *"regarde
pour tous les jeux"*.

When he asks for a change of behaviour that has a design decision inside (a stake cap, a fee, which
tables take chips), ask one `AskUserQuestion` with a recommended option before building.

End every batch with: what changed, in French, short, then the **one** `.bat` to run.

