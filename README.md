# Item Casino

A server-authoritative casino for **Minecraft 1.21.11 / NeoForge 21.11.42** (Java 21). Players gamble
real items — or chips held on a Chip Card — at seven tables, against the house, each other, or a shared
server-wide pot. Every item is priced by a valuation engine that walks the loaded recipe graph.

> **Working on the code?** Read [`HANDOFF.md`](HANDOFF.md) first: how the project is built and checked,
> the architecture, the 1.21.11 API traps, the bugs already fixed, and what is still open.

## Content

| Table | Stake | Return | In short |
|---|---|---|---|
| **Upgrader** | items or chips | 90 % | Bet on a target item; a wheel spins at fair odds minus 10 %. |
| **Predict the Dice** | chips | 97 % | Pick a chance (1–95 %) and a side of the line; the server rolls 0.00–99.99. |
| **Blackjack** | items or chips | ~99.4 % | One deck, dealer stands on soft 17, double, surrender, 3:2 naturals. |
| **Coin Flip** | items or chips | 100 % | A duel between two players with matching stakes. |
| **Slot Machine** | items or chips | 90 % | Three reels, triples up to ×800, pairs from Iron up. |
| **Mine Field** | chips | 97 % | 5×5 board, 1–24 mines, cash out whenever you like. |
| **The Vault** | items or chips | 80 % | Draw for a chosen share of the shared pot. |

* **Cashier** block and **Chip Card**: items become chips at full value; chips become diamonds,
  emeralds, gold… at value plus a 2 % fee. The card is bearer: whoever holds it owns the chips.
* **Shared jackpot**: losses at the house tables feed a server-wide pot, won at the Vault or, rarely, on
  any wager.
* **Pocket devices** for the Upgrader, the Dice and Blackjack.
* **Gilded Gil**, a gambling goblin who appears when gold is thrown on the ground.
* Per-player **stats** (the `$` button in the inventory) and a **mailbox** for winnings that cannot be
  handed over immediately.
* `/casino value | odds | diagnose | dump | rebuild` for operators.

Every number above is configurable in the server config (`itemcasino-server.toml`). The mod is English
only.

## Fairness and safety

* Outcomes are decided once on the server when a wager is committed, saved, and only replayed by the
  client; nothing the client sends can change a result. A result is never shown before its animation.
* Wagers are escrowed atomically; a settle cannot pay twice (`SETTLING` is a one-way door); a session
  saved mid-game is repaired forward from its decided outcome.
* Payouts go to the inventory, then to the casino mailbox, never nowhere. Breaking a table settles it and
  returns every stake to its owner.
* Shulker boxes and bundles are refused; packets are rate limited; every list codec is capped.

## Building

There is no Gradle wrapper in the tree. On the author's machine the three scripts at the root call a
local Gradle 9.2.1:

| Script | Does |
|---|---|
| `run-build.bat` | `gradle build` (compiles, runs the JUnit suite) → `build-errors.txt`, `build-status.txt` |
| `run-gametest.bat` | `gradle build runGameTestServer` (22 in-world game tests) → `gametest-out.txt` |
| `run-client.bat` | `gradle runClient` |

Without Gradle, `tools/offline/` compiles the whole mod against the real NeoForge jars and runs the
JUnit suite, the pure-core self-test and the static checks (see `HANDOFF.md` §5).

## Layout

```
src/main/java/com/itemcasino/
  core/        pure Java, no Minecraft imports: values, odds, blackjack, dice, mines, chips, state machine
  session/     the games (one CasinoSession per table), hosted by a block, a pocket item or the goblin
  block/ menu/ client/ network/ registry/
  valuation/   recipe harvesting and item prices     jackpot/  the shared pot
  player/      stats and mailbox                      gametest/ in-world tests
src/test/java/ JUnit for the core
tools/         CoreSelfTest, audit_overrides.py, texture generators, offline toolkit
```

License: MIT.
