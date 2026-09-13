# MisereHex MiniZero-Ludii Bridge

Bridges a [MiniZero](https://github.com/rlglab/minizero)-trained MisereHex model
(played via MiniZero's GTP console) with [Ludii](https://ludii.games)'s Hex
implementation (Misere ruleset), so the two can play automated matches for
strength evaluation.

## Why this exists

This is one half of a research experiment: comparing a MiniZero-trained
MisereHex agent against Ludii's own AI on an equivalent ruleset. MiniZero has
no built-in way to talk to Ludii (it doesn't understand `.lud` files, and
Ludii doesn't speak GTP), so this repo is the missing middle layer.

## Architecture

Ludii and MiniZero each run their **own** complete rule engine independently;
this bridge does not need to translate rules, only individual moves, one at a
time, for the duration of a match.

Two integration styles exist in the wild:

- **JNI embedding** (what [Polygames](https://github.com/facebookincubator/Polygames/tree/master/src/games/ludii)
  does): embed a JVM directly inside a C++ process and call into Ludii's Java
  objects. Fast and tight, but a lot of native-interop machinery to build.
- **External subprocess + text protocol** (what this repo does): a Java
  `util.AI` agent, loaded into Ludii, spawns MiniZero's console executable as
  a child process and talks to it over stdin/stdout using GTP (`play`,
  `genmove`). MiniZero already has a fully working GTP console with a trained
  model behind it, so this needs no changes on the MiniZero side at all.

We deliberately did **not** go the `jpy`/Py4J route (like
[LudiiPythonAI](https://github.com/Ludeme/LudiiPythonAI)) because MiniZero's
existing Python bindings (`minizero/learner/pybind.cpp`) only expose the game
*environment* (rules simulation), not the trained-model MCTS search itself —
that only exists in the C++ console/self-play code path. Driving the console
as a subprocess is the only route that doesn't require new binding work on
the MiniZero side.

```
Ludii (java, engine authoritative)
  └─ util.AI subclass (this repo)
       └─ spawns & holds open ──> MiniZero console subprocess (GTP over stdin/stdout)
```

## Repo layout

```
pom.xml                                     Maven project (Java 17 target)
libs/Ludii-1.3.14.jar                       Vendored Ludii dependency (not on Maven Central)
src/main/java/bridge/Main.java              Smoke test: loads MisereHex 11x11 via Ludii
src/main/java/bridge/MiniZeroAI.java        The bridge itself: a util.AI that drives a MiniZero console subprocess
src/main/java/bridge/TestMatch.java         Runs one full automated MiniZeroAI-vs-MiniZeroAI match end to end
src/main/java/bridge/Inspect*.java          One-off tools used to verify Ludii's actual API/behaviour against the
                                             jar directly (coordinate system, swap-move representation) instead of
                                             trusting docs - not part of the bridge, kept for reference
```

## Prerequisites

- JDK 17+ (developed against JDK 24; compiler target is set to 17 for safety)
- Maven (tested with 3.9.16 — see note below, it's not on winget, download
  the binary zip from https://maven.apache.org/download.cgi and add
  `bin/` to your PATH)
- `libs/Ludii-1.3.14.jar` is already committed in this repo, so no separate
  download is needed to build

On the MiniZero side, you need:
- A built executable: `build/miserehex/minizero_miserehex`
- A trained model checkpoint, e.g. `miserehex_az_manual/model/weight_iter_50000.pt`
- The training config, `miserehex.cfg`

## Build

```bash
mvn compile
```

## Smoke test

Confirms the Ludii dependency is wired correctly and that MisereHex 11x11
with the Misere ruleset actually loads:

```bash
mvn compile
java -cp "target/classes;libs/Ludii-1.3.14.jar" bridge.Main
```

Expected output:
```
Loaded game: Hex
Num players: 2
Ludii dependency is wired correctly.
```

> **Note:** `mvn exec:java` does **not** work here — its classloader does not
> pick up `system`-scoped dependencies (i.e. the vendored `Ludii.jar`), and
> throws `ClassNotFoundException`. Always run with plain `java -cp` as shown
> above instead.

## Running a match

`MiniZeroAI` hardcodes its Docker container name and MiniZero paths at the
top of the class — edit those constants to match your setup (they currently
point at a specific running container; a real config file would be a
sensible follow-up once this is used for more than one machine). It also
requires a MiniZero container that's already running detached
(`scripts/start-container.sh -d`, then `docker exec` into it) so the
subprocess it spawns doesn't die with a foreground terminal.

`TestMatch` runs one full MiniZeroAI-vs-MiniZeroAI game end to end, using
Ludii's own "Running Trials" loop:

```bash
mvn compile
java -cp "target/classes:libs/Ludii-1.3.14.jar" bridge.TestMatch
```

Set `MiniZeroAI.DEBUG = true` to print every GTP command sent/received and
every move translation - useful when something looks wrong, since the two
engines never share state and any desync only surfaces as a confusing
"invalid action" several moves later.

## Rule alignment (MiniZero ↔ Ludii)

The two engines never share a rule definition, so every setting below has to
be matched by hand. Verified so far:

| Setting | MiniZero (`miserehex.cfg`) | Ludii | Verified? |
|---|---|---|---|
| Board size | `env_board_size=11` | `"Board Size/11x11"` | ✅ confirmed loadable |
| Misère win condition | hardcoded in `MisereHexEnv::act` (connecting your own edges = you lose) | `"End Rules/Misere"` | ✅ confirmed loadable |
| Swap/pie rule | hardcoded `true` at compile time — **not** settable via cfg/`-conf_str` for the `MISEREHEX` build (see gotcha below) | `"Swap Rules/On"` (also Ludii's default) | ✅ values match |

All three confirmed by extracting and reading the actual ruleset source
straight out of the jar (`unzip -p libs/Ludii-1.3.14.jar
"lud/board/space/connection/Hex.lud"`), and by successfully loading:
```java
GameLoader.loadGameFromName("Hex.lud",
    List.of("Board Size/11x11", "End Rules/Misere", "Swap Rules/On"));
```
This is more reliable than reading Ludii Desktop's Options menu by eye, and
doubles as documentation of Hex's actual option strings for the rest of this
project.

**The swap representations really do differ, as suspected**, and this is now
implemented in `MiniZeroAI`: MiniZero signals "swap" by playing on the *same
cell* as the game's first move ([miserehex.cpp](https://github.com/rlglab/minizero/blob/main/minizero/environment/miserehex/miserehex.cpp#L28-L47)),
while Ludii exposes it as a distinct legal `Move` with
`actionType() == ActionType.Swap` (confirmed empirically with
`InspectSwap.java` — its `to()`/`from()` are both `-1`, so it can't be
matched by site like a normal move). `MiniZeroAI` translates between the two
representations in both directions, tracking the first move's site for the
lifetime of the game.

## Known gotchas

- **Ludii and MiniZero label board columns differently — never compare
  coordinate strings directly.** Confirmed with `InspectCoords.java`: on the
  11x11 board, Ludii's columns are plain `A`-`K` (no skipped letters), while
  MiniZero's skip `I` (`A`-`H`, then `J`-`L`). Column 9 (0-indexed 8) is `"I"`
  in Ludii but `"J"` in MiniZero — same physical cell, different letter.
  `MiniZeroAI` never touches label strings for translation; it always goes
  through the 0-indexed `(row, col)` pair via `Cell.row()/col()`.
- **`genmove` silently desyncs MiniZero's own board the moment it resigns.**
  `ZeroActor::think()` ([zero_actor.cpp:36-49](https://github.com/rlglab/minizero/blob/main/minizero/actor/zero_actor.cpp#L36-L49))
  calls `act()` on its *own* selected action unconditionally (whenever GTP's
  `genmove`, as opposed to `reg_genmove`, is used) — including when it then
  reports `"Resign"`, whose reply text famously does not say which cell it
  actually committed. Found this after a match ran fine for 81 moves and
  then failed with a baffling "invalid action" on an apparently-empty cell:
  a resign a few moves earlier had advanced MiniZero's internal board to a
  cell nobody told the bridge about, and it drifted from Ludii's real board
  from that point on. Fixed by using `reg_genmove` (which does *not*
  auto-commit) and always sending an explicit `play` afterward for whatever
  move the bridge actually decided on — MiniZero's board is now driven
  exclusively by the bridge, never by its own internal side effects.
- **Resigning is disabled entirely rather than handled.** No Ludii-side
  equivalent for a genuine resignation was confirmed (Ludii does have
  `other.action.others.ActionForfeit`, but it's unclear whether
  `Model.startNewStep()` accepts a Move outside `game.moves(context)`'s own
  legal list — untested). Simpler and arguably more useful for evaluation
  anyway: `initAI` passes `zero_disable_resign_ratio=1.0` in MiniZero's
  `-conf_str`, which is a **runtime-only** setting (no retraining needed) that
  makes `ZeroActor` always play a game out instead of resigning early
  ([zero_actor.cpp:26](https://github.com/rlglab/minizero/blob/main/minizero/actor/zero_actor.cpp#L26)),
  so you see the model's actual moves to the end rather than an early
  concession. Confirmed with a full match producing zero `"Resign"` replies.
  The `resign` branch in `MiniZeroAI.selectAction` is now unreachable in
  practice, kept only as a defensive fallback.
- **`env_hex_use_swap_rule` cannot be set in `miserehex.cfg`.** MiniZero
  registers this config key per game type via an `#if GAME_TYPE ... #elif
  HEX ... #endif` chain in `minizero/config/configuration.cpp`, and there is
  no `MISEREHEX` branch — only `HEX` gets it registered as a valid option.
  The underlying variable still exists and defaults to `true` (which is what
  `MisereHexEnv` actually uses at runtime), but the `MISEREHEX` build's
  config parser will reject the key outright (`Invalid key
  "env_hex_use_swap_rule"...`, exit code 255) if you try to set it — found
  this the hard way by adding it to the cfg and watching the console
  subprocess refuse to start. Don't add it to `miserehex.cfg`; the value is
  always `true` for this build regardless, which happens to already match
  Ludii's `"Swap Rules/On"` default.
- The Ludii jar's actual package for `GameLoader` is **`other.GameLoader`**,
  not `player.utils.loading.GameLoader` as some outdated docs/summaries
  suggest. Verified by inspecting the jar directly:
  `javap -cp libs/Ludii-1.3.14.jar -public other.GameLoader`. If a class
  can't be found, don't trust the docs — inspect the actual jar.
- Maven isn't available via `winget` on Windows; installed manually from the
  official binary zip instead.

## References

- [Deep Learning for General Game Playing with Ludii and Polygames](https://arxiv.org/pdf/2101.09562)
- [Polygames' Ludii bridge (JNI, C++)](https://github.com/facebookincubator/Polygames/tree/master/src/games/ludii)
- [Ludii: Basic API for AI Development](https://ludiitutorials.readthedocs.io/en/latest/basic_ai_api.html)
- [Ludii: Running Trials](https://ludiitutorials.readthedocs.io/en/latest/running_trials.html)
- [LudiiExampleAI](https://github.com/Ludeme/LudiiExampleAI)
- [LudiiPythonAI (jpy-based, not the approach used here)](https://github.com/Ludeme/LudiiPythonAI)
- [MiniZero GTP console docs](https://github.com/rlglab/minizero/blob/main/docs/Console.md)
