# Changelog

Versions follow `mod_version` in `gradle.properties`. Each release is tagged `vX.Y.Z` and its jar is
attached to the matching [GitHub release](https://github.com/SVecro/ItemCasino/releases).

## 1.0.0 — unreleased

The first stable release. Everything below was worked on as 0.3.0, which never shipped on its own.

**In French too.** The mod is translated into French, and follows the language each player's game is
set to. Every other language still reads English.

**Leaner jar.** The in-game test suite no longer ships in the jar players download; it lives in a
source set of its own and still runs on every build.

**Blackjack seats three.**
- A Blackjack table seats up to three players against one dealer and one shoe. The chairs act in
  turn, and each settles on its own. You see your neighbours' bets and cards; your own hand is always
  the one in front of you. The pocket Blackjack still seats one.
- A lobby between hands. Alone at the table, *Deal* deals at once. When two or more players have the
  table open, the button reads *Ready*: the first *Ready* starts a 10-second countdown
  (`blackjack.lobby_seconds`), and the cards come out as soon as everyone with the table open is
  ready, or when the countdown ends, to whoever is ready by then. A player who never says ready sits
  the hand out and keeps their bet, so nobody who walked away stops the table or loses anything.
- Pressing *Ready* again (the button then reads *Cancel*), any change to your box or your chip bet,
  or closing the screen withdraws your *Ready*. Every stake is checked again when the cards come out.
- 15 seconds per move in a hand shared by two or more chairs (`blackjack.shared_action_seconds`);
  a hand played alone keeps 60 (`blackjack.player_action_seconds`). A chair whose clock runs out
  stands, and play moves on.

**Fixes**
- A shared hand interrupted by a server stop, a crash or the chunk unloading lost what the second and
  third chairs were owed when the table was loaded again. They are now paid on the first tick.
- A stake changed after *Ready* was dealt in without being checked, so a blacklisted, unpriced or
  too-cheap item, or a card under one chip, could be played. It is now checked at the deal; a chair
  that fails sits the hand out and is told why.
- The old `blackjack_deal` packet ignored `safety.disabled_games`.
- At a shared table, the player who pressed *Deal* collected what the first chair was owed: its
  winnings, its refund and its stats. Seat 0's money now follows seat 0.
- After a hand, the lobby's ready marks and countdown never showed: the finished hand stayed on the
  felt until the next one was dealt. It is now swept away (below).
- At a shared blackjack table, a player with an empty box saw *Ready* lit as soon as a neighbour
  had a stake; pressing it did nothing. The button now stays dark until your own box holds a stake.
- A spectator, or a chair sitting the hand out, saw "--" as the dealer's total all hand: the gauge
  waited for the viewer's own first card. It now follows the dealer's cards.
- Typing in the Upgrader's search box: E closed the table, Q dropped the hovered item and digits
  swapped it with the hotbar. The box now keeps every key while it has the focus.

**Changes**
- A finished blackjack hand stays on the felt for 2.5 seconds, long enough to read who won, then is
  swept off the table; at once if someone at the table has already said *Ready*.
- Casino tables are wooden furniture: they break by hand or faster with an axe, as fast as a
  crafting table, and no longer need a pickaxe. They still resist explosions and are not set alight by
  lava, because a table can be holding a stake. The Game Core stays a pickaxe block.
- Network protocol version 7: a 0.3.0 server refuses 0.2.0 clients, and the other way round.

## 0.2.0 — 2026-09-17

- **Every game explains itself.** Each table's (i) panel now teaches the game: what you put in and
  press, what it pays, the controls. The Upgrader, Blackjack and Coin Flip had none before.
- **Game Core** (8 redstone around a gold ingot), the one ingredient every table shares.
- **A new crafting tree, cheap and all in one shape:** a Game Core, five planks and the item that
  names the table. Nothing in the mod costs a diamond; only the Vault needs a nether star. The pocket
  devices are their table in 4 paper and 4 gold nuggets.
- **Recipe advancements:** the recipes unlock in the recipe book as you pick up their ingredients.
- **Removed:** Gilded Gil, the wandering dealer, with his entity, summon and config section.

## 0.1.0 — 2026-09-17

First release.

- Seven games: Upgrader, Predict the Dice, Blackjack, Coin Flip, Slot Machine, Mine Field and the
  Vault, played with items or with chips.
- The Cashier and the Chip Card: currencies to chips at full value and back again for a 2 % fee.
- A shared jackpot fed by losses at the house tables, won only at the Vault.
- Pocket Upgrader, Dice and Blackjack.
- A valuation engine that prices every item from the recipe graph, plus `/casino` commands to
  inspect it.
- Per-player stats, a mailbox for winnings that cannot be handed over, tooltips, and the casino value
  of any item on Shift.
- Every outcome drawn on the server from a `SecureRandom` and only replayed by the client. Every stake
  held in escrow.
