# Changelog

Versions follow `mod_version` in `gradle.properties`. Each release is tagged `vX.Y.Z` and its jar is
attached to the matching [GitHub release](https://github.com/SVecro/ItemCasino/releases).

## 0.3.0 — unreleased

**Blackjack seats three.**
- A Blackjack table seats up to three players against one dealer and one shoe. The chairs act in
  turn, and each settles on its own. You see your neighbours' bets and cards; your own hand is always
  the one in front of you. The pocket Blackjack still seats one.
- A lobby between hands: at a shared table the button reads *Ready*, not *Deal*. The first *Ready*
  starts a countdown (20 seconds, `blackjack.ready_seconds`). The cards come out when every chair with
  a bet is ready, or when the countdown ends. A chair that never says ready sits the hand out and keeps
  its bet, so a player who walked away never stops the table and never loses anything.
- Changing your bet, pressing *Ready* again or closing the screen withdraws your *Ready*.
- A chair that lets its turn clock run out stands, and play moves on.

**Fixes**
- At a shared table, the player who pressed *Deal* collected what the first chair was owed: its
  winnings, its refund and its stats. Seat 0's money now follows seat 0.

**Changes**
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
