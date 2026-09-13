package bridge;

import java.util.Arrays;

import game.Game;
import game.rules.play.moves.Moves;
import other.GameLoader;
import other.context.Context;
import other.move.Move;
import other.trial.Trial;

/**
 * One-off inspection tool: after the first move is played, print every legal
 * move's toString()/actionType()/to() so we can see how Ludii represents the
 * "swap" choice for the second player, instead of guessing.
 */
public class InspectSwap {
    public static void main(String[] args) {
        Game game = GameLoader.loadGameFromName(
            "Hex.lud",
            Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On")
        );
        Trial trial = new Trial(game);
        Context context = new Context(game, trial);
        game.start(context);

        // Play a first move: site 0 (label A1)
        Moves legal = game.moves(context);
        Move first = legal.moves().get(0);
        System.out.println("First move played: " + first + " to=" + first.to());
        game.apply(context, first);

        System.out.println("--- Legal moves for player 2 (looking for the swap option) ---");
        Moves legal2 = game.moves(context);
        for (Move m : legal2.moves()) {
            System.out.println("move=" + m + " | actionType=" + m.actionType() + " | to=" + m.to() + " | from=" + m.from());
        }
        System.out.println("Total legal moves for player 2: " + legal2.moves().size());
    }
}
