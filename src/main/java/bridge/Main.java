package bridge;

import java.util.Arrays;

import game.Game;
import other.GameLoader;

/**
 * Smoke test: confirms the Ludii.jar dependency is wired correctly, and that
 * MisereHex can actually be loaded with the same board size / ruleset used
 * for the MiniZero training run, before any bridge logic (util.AI wrapper,
 * GTP subprocess, coordinate mapping) is written.
 */
public class Main {
    public static void main(String[] args) {
        Game game = GameLoader.loadGameFromName(
            "Hex.lud",
            Arrays.asList("Board Size/11x11", "End Rules/Misere", "Swap Rules/On")
        );
        System.out.println("Loaded game: " + game.name());
        System.out.println("Num players: " + game.players().count());
        System.out.println("Ludii dependency is wired correctly.");
    }
}
