package bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import game.Game;
import game.rules.play.moves.Moves;
import other.AI;
import other.action.ActionType;
import other.context.Context;
import other.move.Move;
import other.topology.Cell;
import other.trial.Trial;

/**
 * Wraps a MiniZero console subprocess (talking GTP over stdin/stdout) as a
 * Ludii {@link AI}. One instance = one persistent MiniZero process for the
 * lifetime of a match; do not share an instance across concurrent matches.
 *
 * Coordinates are never compared as label strings between the two engines -
 * Ludii's Hex board uses plain A-K for 11 columns, while MiniZero's skips the
 * letter 'I' (A-H, J-L). Every conversion goes through the 0-indexed
 * (row, col) pair via Ludii's {@code Cell.row()/col()} as the common ground.
 *
 * Swap-rule handling: MiniZero has no dedicated "swap" action - its
 * environment (see MisereHexEnv::act) treats playing on the exact same cell
 * as the game's first move as the swap signal. Ludii instead exposes swap as
 * a distinct legal {@link Move} with {@code actionType() == ActionType.Swap}.
 * This class translates between the two representations in both directions.
 */
public class MiniZeroAI extends AI {

    // --- Adjust these to match your environment ---
    private static final String DOCKER_CONTAINER = "cbadd65e803e";
    private static final String MINIZERO_EXECUTABLE = "build/miserehex/minizero_miserehex";
    private static final String MINIZERO_CONF_FILE = "miserehex.cfg";
    private static final String MINIZERO_MODEL = "miserehex_az_manual/model/weight_iter_50000.pt";
    private static final int NUM_SIMULATIONS = 400;
    private static final boolean DEBUG = false;

    private Process process;
    private BufferedReader stdout;
    private Writer stdin;
    private int playerID; // this AI's originally-assigned seat, for debug logging only

    /** Site of the very first move played in the game; needed to recognise/emit the swap signal. Null until the first move is known. */
    private Integer firstMoveSite;

    public MiniZeroAI() {
        setFriendlyName("MiniZero");
    }

    @Override
    public void initAI(final Game game, final int playerID) {
        this.playerID = playerID;
        this.firstMoveSite = null;
        try {
            final ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", "-i", DOCKER_CONTAINER,
                MINIZERO_EXECUTABLE,
                "-mode", "console",
                "-conf_file", MINIZERO_CONF_FILE,
                "-conf_str", "nn_file_name=" + MINIZERO_MODEL
                    + ":actor_num_simulation=" + NUM_SIMULATIONS
                    + ":actor_select_action_by_count=true"
                    + ":actor_select_action_by_softmax_count=false"
                    + ":actor_use_dirichlet_noise=false"
                    + ":actor_use_gumbel_noise=false"
                    + ":program_quiet=true"
                    // Always play the game out instead of resigning. Resigning
                    // desyncs MiniZero's own board from Ludii's (see the
                    // reg_genmove comment below) and isn't handled properly on
                    // the Ludii side yet - this makes the resign path dead
                    // code rather than something we still need to get right.
                    // Runtime-only setting; does not require retraining.
                    + ":zero_disable_resign_ratio=1.0"
            );
            // Let stderr go straight to this JVM's own stderr instead of a pipe
            // nobody drains - avoids the subprocess blocking if it ever does
            // write something despite program_quiet=true.
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to start MiniZero subprocess", e);
        }
    }

    @Override
    public void closeAI() {
        try {
            if (stdin != null) { stdin.close(); } // EOF on stdin makes MiniZero's `while (getline(cin, ...))` loop exit cleanly
        } catch (final IOException ignored) {}
        if (process != null) { process.destroy(); }
    }

    @Override
    public Move selectAction(final Game game, final Context context, final double maxSeconds, final int maxIterations, final int maxDepth) {
        try {
            final List<? extends Cell> cells = game.board().topology().cells(); // list index == Ludii site id
            final Trial trial = context.trial();

            if (DEBUG) {
                System.out.println("[AI" + playerID + "] selectAction called. numMoves=" + trial.numMoves()
                    + " lastMove=" + trial.lastMove() + " mover=" + context.state().mover());
            }

            // 1. Tell MiniZero what the opponent just did, if anything.
            final Move lastMove = trial.lastMove();
            if (lastMove != null) { sendOpponentMove(lastMove, cells); }

            // 2. Ask MiniZero what it WOULD play, WITHOUT letting it auto-commit
            //    to its own board (`reg_genmove` instead of `genmove` - see
            //    ZeroActor::think: `genmove` always calls act() internally
            //    before reporting "Resign", silently advancing MiniZero's own
            //    board to a cell it never actually tells you, guaranteeing a
            //    desync the moment a resign happens). We commit explicitly via
            //    `play` below instead, once we know exactly what Ludii will do
            //    with it, so MiniZero's board and Ludii's board never diverge.
            //    `context.state().mover()` is read at call time (not cached
            //    from initAI) because a swap can reassign which player role
            //    this seat controls partway through the game.
            final char myColor = colorForPlayer(context.state().mover());
            final String coord = gtp("reg_genmove " + myColor);
            if (DEBUG) { System.out.println("[AI" + playerID + "] reg_genmove " + myColor + " -> " + coord); }

            final Moves legalMoves = game.moves(context);
            final Move chosen;
            final String coordToCommit;

            if (coord.equalsIgnoreCase("resign")) {
                // Shouldn't happen: zero_disable_resign_ratio=1.0 in initAI's
                // -conf_str makes MiniZero always play the game out instead of
                // resigning. Kept as a defensive fallback (arbitrary legal
                // move - not a real resignation policy) in case that ever
                // changes, rather than crashing the match outright.
                chosen = legalMoves.moves().get(0);
                coordToCommit = (chosen.actionType() == ActionType.Swap)
                    ? siteToCoord(firstMoveSite, cells)
                    : siteToCoord(chosen.to(), cells);
            } else if (firstMoveSite != null && trial.numMoves() == 1 && coordToSite(coord, cells) == firstMoveSite) {
                // MiniZero's swap signal: playing on the same cell as the game's first move.
                Move swapMove = null;
                for (final Move m : legalMoves.moves()) {
                    if (m.actionType() == ActionType.Swap) { swapMove = m; break; }
                }
                if (swapMove == null) { throw new IllegalStateException("MiniZero signalled swap, but Ludii offered no Swap move"); }
                chosen = swapMove;
                coordToCommit = coord; // same cell as move 1, exactly what reg_genmove returned
            } else {
                final int site = coordToSite(coord, cells);
                if (firstMoveSite == null) { firstMoveSite = site; }
                Move found = null;
                for (final Move m : legalMoves.moves()) {
                    if (m.to() == site) { found = m; break; }
                }
                if (found == null) { throw new IllegalStateException("MiniZero chose " + coord + " (site " + site + "), which Ludii does not consider legal here"); }
                chosen = found;
                coordToCommit = coord;
            }

            // 3. Commit exactly this move to MiniZero's own board so it never
            //    silently diverges from what actually happens in Ludii.
            gtp("play " + myColor + " " + coordToCommit);
            if (DEBUG) { System.out.println("[AI" + playerID + "] committed play " + myColor + " " + coordToCommit); }

            return chosen;
        } catch (final IOException e) {
            throw new RuntimeException("GTP communication with MiniZero failed", e);
        }
    }

    private void sendOpponentMove(final Move lastMove, final List<? extends Cell> cells) throws IOException {
        final char opponentColor = colorForPlayer(lastMove.mover());
        final String coord;
        if (lastMove.actionType() == ActionType.Swap) {
            if (firstMoveSite == null) { throw new IllegalStateException("Opponent swapped, but no first move was recorded"); }
            coord = siteToCoord(firstMoveSite, cells);
        } else {
            coord = siteToCoord(lastMove.to(), cells);
            if (firstMoveSite == null) { firstMoveSite = lastMove.to(); }
        }
        if (DEBUG) {
            System.out.println("[AI" + playerID + "] telling MiniZero about opponent move: to=" + lastMove.to()
                + " mover=" + lastMove.mover() + " actionType=" + lastMove.actionType()
                + " -> play " + opponentColor + " " + coord);
        }
        gtp("play " + opponentColor + " " + coord);
    }

    /** Ludii player role 1/2 -> MiniZero color, matching charToPlayer/playerToChar in base_env.cpp (Player1='B', Player2='W'). */
    private char colorForPlayer(final int playerRole) {
        return (playerRole == 1) ? 'B' : 'W';
    }

    /** MiniZero-side coordinate string: letters skip 'I' (see SGFLoader::actionIDToBoardCoordinateString). */
    private String siteToCoord(final int site, final List<? extends Cell> cells) {
        final Cell cell = cells.get(site);
        final int col = cell.col();
        final int row = cell.row();
        final char letter = (char) ('A' + col + (col >= 8 ? 1 : 0));
        return "" + letter + (row + 1);
    }

    private int coordToSite(final String coord, final List<? extends Cell> cells) {
        final char letterChar = Character.toUpperCase(coord.charAt(0));
        final int col = (letterChar - 'A') - (letterChar > 'I' ? 1 : 0);
        final int row = Integer.parseInt(coord.substring(1)) - 1;
        for (final Cell c : cells) {
            if (c.row() == row && c.col() == col) { return c.index(); }
        }
        throw new IllegalStateException("No Ludii cell found for MiniZero coordinate " + coord);
    }

    /**
     * Sends one GTP command and returns its reply text (the "= "/"? " prefix
     * and the terminating blank line are stripped). Assumes single-line
     * replies, which holds for `play` and `genmove` - extend if other GTP
     * commands are added later.
     */
    private String gtp(final String command) throws IOException {
        stdin.write(command + "\n");
        stdin.flush();
        final String line = stdout.readLine();
        if (line == null) { throw new IOException("MiniZero subprocess closed its output unexpectedly"); }
        if (line.startsWith("?")) { throw new IOException("MiniZero GTP error: " + line); }
        final String reply = line.length() > 1 ? line.substring(2).trim() : "";
        stdout.readLine(); // consume the blank line that terminates every GTP reply
        return reply;
    }
}
