package bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import game.Game;
import other.AI;
import other.GameLoader;
import other.context.Context;
import other.model.Model;
import other.trial.Trial;

/**
 * End-to-end smoke test: runs one full automated MisereHex 11x11 match with
 * a {@link MiniZeroAI} on both sides, using the loop from Ludii's official
 * "Running Trials" tutorial. Not part of the bridge itself - this is just
 * the smallest possible proof that MiniZeroAI, the GTP subprocess, the
 * coordinate mapping, and the swap-rule translation all work together.
 */
public class TestMatch {
    public static void main(final String[] args) {
        final Game game = GameLoader.loadGameFromName(
            "Hex.lud",
            Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On")
        );
        final Trial trial = new Trial(game);
        final Context context = new Context(game, trial);

        final List<AI> ais = new ArrayList<>();
        ais.add(null); // Ludii uses 1-based player indexing
        ais.add(new MiniZeroAI());
        ais.add(new MiniZeroAI());

        game.start(context);
        for (int p = 1; p <= game.players().count(); ++p) {
            ais.get(p).initAI(game, p);
        }

        final Model model = context.model();
        while (!trial.over()) {
            model.startNewStep(context, ais, 1.0);
            System.out.println("Move " + trial.numMoves() + ": " + trial.lastMove());
        }

        System.out.println("--- Match over ---");
        final double[] ranking = trial.ranking();
        for (int p = 1; p <= game.players().count(); ++p) {
            System.out.println("Player " + p + " (agent " + context.state().playerToAgent(p) + ") rank: " + ranking[p]);
        }

        for (int p = 1; p <= game.players().count(); ++p) {
            ais.get(p).closeAI();
        }
    }
}
