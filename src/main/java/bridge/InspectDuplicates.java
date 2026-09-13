package bridge;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import game.Game;
import other.GameLoader;
import other.topology.Cell;

/** One-off tool: check whether any two distinct Ludii cells share the same (row, col) pair. */
public class InspectDuplicates {
    public static void main(String[] args) {
        Game game = GameLoader.loadGameFromName(
            "Hex.lud",
            Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On")
        );
        List<? extends Cell> cells = game.board().topology().cells();
        Map<String, Integer> seen = new HashMap<>();
        int duplicates = 0;
        for (Cell c : cells) {
            String key = c.row() + "," + c.col();
            if (seen.containsKey(key)) {
                System.out.println("DUPLICATE row/col " + key + ": site " + seen.get(key) + " and site " + c.index());
                duplicates++;
            } else {
                seen.put(key, c.index());
            }
        }
        System.out.println("Total cells: " + cells.size() + ", duplicates found: " + duplicates);

        // Also print site 100 specifically, since that's what failed in TestMatch
        Cell c100 = cells.get(100);
        System.out.println("site=100 row=" + c100.row() + " col=" + c100.col() + " label=" + c100.label());
    }
}
