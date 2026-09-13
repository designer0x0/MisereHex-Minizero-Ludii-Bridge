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
pom.xml                                  Maven project (Java 17 target)
libs/Ludii-1.3.14.jar                    Vendored Ludii dependency (not on Maven Central)
src/main/java/bridge/Main.java           Smoke test: loads MisereHex 11x11 via Ludii
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

## Rule alignment (MiniZero ↔ Ludii)

The two engines never share a rule definition, so every setting below has to
be matched by hand. Verified so far:

| Setting | MiniZero (`miserehex.cfg`) | Ludii | Verified? |
|---|---|---|---|
| Board size | `env_board_size=11` | `"Board Size/11x11"` | ✅ confirmed loadable |
| Misère win condition | hardcoded in `MisereHexEnv::act` (connecting your own edges = you lose) | `"End Rules/Misere"` | ✅ confirmed loadable |
| Swap/pie rule | `env_hex_use_swap_rule=true` (default) | `"Swap Rules/On"` (also Ludii's default) | ✅ confirmed loadable |

All three confirmed by extracting and reading the actual ruleset source
straight out of the jar (`unzip -p libs/Ludii-1.3.14.jar
"lud/board/space/connection/Hex.lud"`), and by successfully loading:
```java
GameLoader.loadGameFromName("Hex.lud",
    List.of("Board Size/11x11", "End Rules/Misere", "Swap Rules/On"));
```
This is more reliable than reading Ludii Desktop's Options menu by eye, and
doubles as documentation of Hex's actual option strings for the rest of this
project. One nuance still worth double-checking later: MiniZero's swap
implementation signals "swap" by playing on the *same cell* as the first
move ([miserehex.cpp](https://github.com/rlglab/minizero/blob/main/minizero/environment/miserehex/miserehex.cpp#L28-L47));
Ludii's `(meta (swap))` mechanism may expose the swap choice differently
(e.g. as a distinct legal move) — this needs to be reconciled once the move
translation layer is written.

## Known gotchas

- The Ludii jar's actual package for `GameLoader` is **`other.GameLoader`**,
  not `player.utils.loading.GameLoader` as some outdated docs/summaries
  suggest. Verified by inspecting the jar directly:
  `javap -cp libs/Ludii-1.3.14.jar -public other.GameLoader`. If a class
  can't be found, don't trust the docs — inspect the actual jar.
- Maven isn't available via `winget` on Windows; installed manually from the
  official binary zip instead.

## Status / roadmap

- [x] Project scaffolding (Maven, vendored Ludii dependency, smoke test)
- [x] Confirmed MisereHex 11x11 loads correctly through Ludii's Java API
- [x] Confirmed exact Ludii swap-rule option string (`"Swap Rules/On"`),
      matches `env_hex_use_swap_rule=true`
- [ ] `util.AI` wrapper (see [Basic API for AI Development](https://ludiitutorials.readthedocs.io/en/latest/basic_ai_api.html)):
      `initAI()` spawns the MiniZero console subprocess once and keeps it
      alive; `selectAction()` sends the opponent's last move via GTP `play`,
      then `genmove`, and converts the result back into a Ludii `Move`
- [ ] Coordinate mapping between MiniZero's `a1`-style board notation and
      Ludii's move representation (including how each side signals a
      swap-rule move)
- [ ] Automated match runner using the loop from
      [Running Trials](https://ludiitutorials.readthedocs.io/en/latest/running_trials.html)
      (`game.start()` → `model.startNewStep()` → `trial.over()` →
      `trial.ranking()`), alternating who plays first, logging win rate
- [ ] Watch out for the player-index/swap-rule caveat called out in Running
      Trials: use `context.state().playerToAgent(p)` when reading rankings,
      not the raw AI list index
- [ ] Validate the whole pipeline on small boards (2x2/3x3) with known
      theoretical misère-Hex outcomes before trusting 11x11 results
- [ ] GPU scheduling: the MiniZero console subprocess needs the same GPU
      used for training — don't run matches while a training run is still
      using it

## References

- [Deep Learning for General Game Playing with Ludii and Polygames](https://arxiv.org/pdf/2101.09562)
- [Polygames' Ludii bridge (JNI, C++)](https://github.com/facebookincubator/Polygames/tree/master/src/games/ludii)
- [Ludii: Basic API for AI Development](https://ludiitutorials.readthedocs.io/en/latest/basic_ai_api.html)
- [Ludii: Running Trials](https://ludiitutorials.readthedocs.io/en/latest/running_trials.html)
- [LudiiExampleAI](https://github.com/Ludeme/LudiiExampleAI)
- [LudiiPythonAI (jpy-based, not the approach used here)](https://github.com/Ludeme/LudiiPythonAI)
- [MiniZero GTP console docs](https://github.com/rlglab/minizero/blob/main/docs/Console.md)
