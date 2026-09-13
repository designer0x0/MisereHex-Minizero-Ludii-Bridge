package bridge;

import java.util.Arrays;
import java.util.List;

import game.Game;
import other.GameLoader;
import other.topology.Cell;

/**
 * One-off inspection tool (not part of the bridge) to empirically determine
 * how Ludii's site indices / labels correspond to MiniZero's "A1"-style
 * board coordinates, instead of guessing from docs.
 */
public class InspectCoords {
    public static void main(String[] args) {
        Game game = GameLoader.loadGameFromName(
            "Hex.lud",
            Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On")
        );
        List<? extends Cell> cells = game.board().topology().cells();
        System.out.println("Num cells: " + cells.size());
        for (Cell c : cells) {
            if (c.row() <= 1) { // just print the first couple of rows to see the pattern
                System.out.println("site=" + c.index() + " row=" + c.row() + " col=" + c.col() + " label=" + c.label());
            }
        }
        // Also print the last few to see the far corner
        for (int i = cells.size() - 5; i < cells.size(); i++) {
            Cell c = cells.get(i);
            System.out.println("site=" + c.index() + " row=" + c.row() + " col=" + c.col() + " label=" + c.label());
        }
    }
}
