package bridge;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.rng.core.RandomProviderDefaultState;

import game.Game;
import other.AI;
import other.GameLoader;
import other.context.Context;
import other.model.Model;
import other.trial.Trial;
import utils.AIFactory;

/**
 * Runs MiniZeroAI vs Ludii's own "Ludii AI" for NUM_GAMES games, alternating
 * who plays first each game, and reports MiniZero's win rate.
 *
 * Win attribution is swap-safe: a swap mid-game reassigns which agent
 * controls which player role (see the "Running Trials" tutorial's warning),
 * so we never assume "player role == the AI object we originally seated
 * there" - we look up context.state().playerToAgent(winningRole) at the end
 * of each game instead.
 */
public class ExperimentRunner {
    private static final int NUM_GAMES = 50;

    // Labels identifying which model is being tested, used to name this
    // run's output folder. Keep in sync with MiniZeroAI's MINIZERO_MODEL.
    private static final String MODEL_TYPE = "AlphaZero";
    private static final String MODEL_NAME = "miserehex_az_manual_weight_iter_50000";

    private static final List<String> GAME_OPTIONS = Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On");

    public static void main(final String[] args) throws IOException {
        final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        final Path gamesDir = Paths.get("games", MODEL_TYPE + "_" + MODEL_NAME + "_" + timestamp);
        Files.createDirectories(gamesDir);
        System.out.println("Output directory: " + gamesDir);

        final Game game = GameLoader.loadGameFromName("Hex.lud", GAME_OPTIONS);

        int miniZeroWins = 0;
        int ludiiWins = 0;

        for (int g = 0; g < NUM_GAMES; ++g) {
            final boolean miniZeroIsAgent1 = (g % 2 == 0); // alternate who is seated as player 1
            final MiniZeroAI miniZero = new MiniZeroAI();
            final AI ludiiAI = AIFactory.createAI("Ludii AI");

            final List<AI> ais = new ArrayList<>();
            ais.add(null); // Ludii uses 1-based player/agent indexing
            ais.add(miniZeroIsAgent1 ? miniZero : ludiiAI);
            ais.add(miniZeroIsAgent1 ? ludiiAI : miniZero);
            final int miniZeroAgentIndex = miniZeroIsAgent1 ? 1 : 2;

            final Trial trial = new Trial(game);
            final Context context = new Context(game, trial);
            game.start(context);
            for (int p = 1; p <= game.players().count(); ++p) {
                ais.get(p).initAI(game, p);
            }

            final Path gameLogPath = gamesDir.resolve(String.format("game_%03d.log", g + 1));
            try (PrintWriter gameLog = new PrintWriter(Files.newBufferedWriter(gameLogPath))) {
                gameLog.println("Game " + (g + 1) + "/" + NUM_GAMES + ": MiniZero is agent " + miniZeroAgentIndex);

                final long startMillis = System.currentTimeMillis();
                final Model model = context.model();
                while (!trial.over()) {
                    model.startNewStep(context, ais, 1.0);
                    gameLog.println("Move " + trial.numMoves() + ": " + trial.lastMove());
                }
                final long elapsedSeconds = (System.currentTimeMillis() - startMillis) / 1000;

                final double[] ranking = trial.ranking();
                int winningRole = -1;
                for (int p = 1; p <= game.players().count(); ++p) {
                    if (ranking[p] == 1.0) { winningRole = p; break; }
                }
                final int winningAgentIndex = context.state().playerToAgent(winningRole);
                final boolean miniZeroWon = (winningAgentIndex == miniZeroAgentIndex);

                if (miniZeroWon) { miniZeroWins++; } else { ludiiWins++; }

                gameLog.println("Winner: " + (miniZeroWon ? "MiniZero" : "Ludii AI") + " (" + elapsedSeconds + " s)");

                // Also save Ludii's own native trial format, viewable/replayable
                // in Ludii Desktop (Load Trial). Uses the TEXT variant, not
                // saveTrialToFile(File, String, RandomProviderDefaultState):
                // that binary version does a raw ObjectOutputStream.writeObject
                // on the whole Trial graph, which always throws
                // NotSerializableException on RandomProviderDefaultState (it
                // doesn't implement Serializable) - and the exception is
                // swallowed internally, so the game log/console still print
                // success even though the file never finished writing. Passing
                // null instead just moves the failure to a NullPointerException
                // (this method dereferences it directly, no null check). The
                // text variant is presumably what Ludii Desktop's own "Save
                // Trial" actually uses - best-effort otherwise, since there's
                // no confirmed real usage example for either overload, and it
                // still needs verifying by actually loading a file in Desktop.
                final Path trialPath = gamesDir.resolve(String.format("game_%03d.trl", g + 1));
                final RandomProviderDefaultState rngState = (RandomProviderDefaultState) context.rng().saveState();
                trial.saveTrialToTextFile(trialPath.toFile(), game.name(), GAME_OPTIONS, rngState);

                System.out.println(String.format(
                    "Game %d/%d: MiniZero was agent %d, %d moves, %d s, winner = %s (log: %s, trial: %s)",
                    g + 1, NUM_GAMES, miniZeroAgentIndex, trial.numMoves(), elapsedSeconds,
                    miniZeroWon ? "MiniZero" : "Ludii AI", gameLogPath, trialPath
                ));
            }

            for (int p = 1; p <= game.players().count(); ++p) { ais.get(p).closeAI(); }
        }

        System.out.println("--- Final result ---");
        System.out.println("MiniZero: " + miniZeroWins + " / Ludii AI: " + ludiiWins
            + " (MiniZero win rate: " + (100.0 * miniZeroWins / NUM_GAMES) + "%)");
    }
}
