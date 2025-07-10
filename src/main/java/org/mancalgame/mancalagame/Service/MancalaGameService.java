package org.mancalgame.mancalagame.Service;

import org.mancalgame.mancalagame.game.MancalaGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * Service class responsible for implementing the core game logic of Mancala,
 * such as making a move, checking for game over conditions, and determining the winner.
 * It operates on a MancalaGame instance.
 */
@Service
public class MancalaGameService {

    private static final Logger logger = LoggerFactory.getLogger(MancalaGameService.class);

    /**
     * Creates and returns a new instance of MancalaGame.
     * @return A freshly initialized MancalaGame.
     */
    public MancalaGame createNewGame() {
        return new MancalaGame();
    }

    /**
     * Executes a move on the given Mancala game board.
     * Applies sowing, capture rules, and updates game state (current player, game over).
     *
     * @param game The MancalaGame instance on which to make the move.
     * @param pitIndex The index of the pit from which to sow stones (0-5 for Player 1, 7-12 for Player 2).
     * @return True if the move was successful and valid, false otherwise (e.g., game already over, invalid pit).
     * @throws IllegalArgumentException if the pit selection is invalid (out of bounds, empty, or opponent's pit).
     * @throws IllegalStateException if the game instance is null.
     */
    public boolean makeMove(MancalaGame game, int pitIndex) {
        if (game == null) {
            logger.error("Cannot make move: Game instance is null");
            throw new IllegalStateException("Game instance is null, cannot make move.");
        }

        // Get a mutable copy of the board to work with.
        // MancalaGame.getBoard() returns a defensive copy, so we're safe to modify this local array.
        int[] board = game.getBoard();
        int currentPlayer = game.getCurrentPlayer();

        if (game.isGameOver()) {
            logger.info("Game is over. Cannot make move.");
            return false; // Cannot make moves if the game is already over.
        }

        // Validate pit selection: ensure it belongs to the current player and has stones.
        if (!isValidPitSelection(game, pitIndex)) {
            String errorMessage = String.format("Invalid move: Pit %d does not belong to Player %d or is empty.", pitIndex, (currentPlayer + 1));
            logger.info(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        int stonesToSow = board[pitIndex];
        board[pitIndex] = 0; // Empty the selected pit

        int currentPit = pitIndex;
        // Sow the stones counter-clockwise
        while (stonesToSow > 0) {
            currentPit = (currentPit + 1) % board.length; // Move to the next pit, wrap around if needed

            // Skip opponent's store: Stones should not be placed in the opponent's mancala.
            if (currentPlayer == 0 && currentPit == MancalaGame.PLAYER2_STORE) {
                continue; // Skip this pit and move to the next one
            }
            if (currentPlayer == 1 && currentPit == MancalaGame.PLAYER1_STORE) {
                continue; // Skip this pit
            }

            board[currentPit]++; // Place one stone in the current pit
            stonesToSow--;       // Decrement stones remaining to sow
        }

        // --- Check for Capture Rule ---
        // A capture occurs if:
        // 1. The last stone landed in an empty pit (which now has 1 stone)
        // 2. The pit is on the current player's side (not their store)
        // 3. The opposite pit has stones in it.

        boolean lastPitOnCurrentPlayerSide =
                (currentPlayer == 0 && currentPit >= MancalaGame.PLAYER1_PIT_START && currentPit <= MancalaGame.PLAYER1_PIT_END) ||
                        (currentPlayer == 1 && currentPit >= MancalaGame.PLAYER2_PIT_START && currentPit <= MancalaGame.PLAYER2_PIT_END);

        if (lastPitOnCurrentPlayerSide && board[currentPit] == 1) { // If pit is now empty (was 0, now 1 after landing)
            int oppositePit = 12 - currentPit; // Calculate the index of the pit directly opposite
            if (board[oppositePit] > 0) { // Check if the opposite pit contains stones to capture
                int playerStore = (currentPlayer == 0) ? MancalaGame.PLAYER1_STORE : MancalaGame.PLAYER2_STORE;

                // Capture: Add stones from opposite pit + the one stone from the landing pit to player's store
                board[playerStore] += (board[oppositePit] + board[currentPit]);
                board[oppositePit] = 0; // Empty the opposite pit
                board[currentPit] = 0; // Empty the pit where the last stone landed
            }
        }

        // IMPORTANT: Update the MancalaGame instance's internal board state.
        // All subsequent checks must operate on this modified state.
        game.setBoard(board);

        // --- Check for Game Over Condition ---
        // A game ends when all of one player's six pits (not their store) are empty.
        boolean player1PitsEmpty = isPlayerPitsEmpty(game, 0);
        boolean player2PitsEmpty = isPlayerPitsEmpty(game, 1);

        if (player1PitsEmpty || player2PitsEmpty) {
            game.setGameOver(true);
            collectRemainingStones(game); // Collect any remaining stones from pits into stores
            determineWinner(game);       // Determine the winner based on final scores
            logger.info("Game Over! Winner determined due to empty pits. Final Board: {}", Arrays.toString(game.getBoard()));
            return true; // Move was successful and led to game over.
        }

        // --- Determine if Current Player Gets Another Turn ("Go Again") ---
        // This happens if the last stone landed in the current player's own store.
        boolean goAgain = (currentPlayer == 0 && currentPit == MancalaGame.PLAYER1_STORE) ||
                (currentPlayer == 1 && currentPit == MancalaGame.PLAYER2_STORE);

        // If not "go again" and the game is not yet over, switch turns to the other player.
        if (!goAgain) {
            game.setCurrentPlayer(1 - currentPlayer); // Switch to the other player (0 becomes 1, 1 becomes 0)
        }

        logger.info("Move successful for Player {}. Current Board: {}", currentPlayer + 1, Arrays.toString(game.getBoard()));
        return true; // Move was successful, game is still in progress.
    }

    /**
     * Checks if a player's selected pit is valid for their turn.
     * A pit is valid if it belongs to the current player and contains stones.
     * @param game The current MancalaGame instance.
     * @param pitIndex The index of the pit to check.
     * @return True if the pit selection is valid, false otherwise.
     */
    private boolean isValidPitSelection(MancalaGame game, int pitIndex) {
        int currentPlayer = game.getCurrentPlayer();
        int[] board = game.getBoard(); // Get the current board state.

        // Check if pit index is within bounds and if the pit is not empty
        if (pitIndex < 0 || pitIndex >= board.length || board[pitIndex] == 0) {
            return false;
        }

        // Check if the selected pit belongs to the current player
        if (currentPlayer == 0) { // Player 1's pits (indices 0-5)
            return pitIndex >= MancalaGame.PLAYER1_PIT_START && pitIndex <= MancalaGame.PLAYER1_PIT_END;
        } else { // Player 2's pits (indices 7-12)
            return pitIndex >= MancalaGame.PLAYER2_PIT_START && pitIndex <= MancalaGame.PLAYER2_PIT_END;
        }
    }

    /**
     * Checks if all pits on a specific player's side (excluding their store) are empty.
     * This is a condition for game over.
     * @param game The current MancalaGame instance.
     * @param player The player (0 or 1) whose pits to check.
     * @return True if all regular pits of the specified player are empty, false otherwise.
     */
    private boolean isPlayerPitsEmpty(MancalaGame game, int player) {
        int[] board = game.getBoard(); // Get the latest board state.
        if (player == 0) { // Check Player 1's pits
            for (int i = MancalaGame.PLAYER1_PIT_START; i <= MancalaGame.PLAYER1_PIT_END; i++) {
                if (board[i] > 0) return false;
            }
        } else { // Check Player 2's pits
            for (int i = MancalaGame.PLAYER2_PIT_START; i <= MancalaGame.PLAYER2_PIT_END; i++) {
                if (board[i] > 0) return false;
            }
        }
        return true;
    }

    /**
     * Collects all remaining stones from both players' regular pits into their respective stores
     * once the game is over.
     * @param game The MancalaGame instance to update.
     */
    private void collectRemainingStones(MancalaGame game) {
        int[] board = game.getBoard(); // Get the latest board state.
        int player1RemainingStones = 0;
        // Collect stones from Player 1's pits
        for (int i = MancalaGame.PLAYER1_PIT_START; i <= MancalaGame.PLAYER1_PIT_END; i++) {
            player1RemainingStones += board[i];
            board[i] = 0; // Empty the pit
        }
        board[MancalaGame.PLAYER1_STORE] += player1RemainingStones; // Add to Player 1's store

        int player2RemainingStones = 0;
        // Collect stones from Player 2's pits
        for (int i = MancalaGame.PLAYER2_PIT_START; i <= MancalaGame.PLAYER2_PIT_END; i++) {
            player2RemainingStones += board[i];
            board[i] = 0; // Empty the pit
        }
        board[MancalaGame.PLAYER2_STORE] += player2RemainingStones; // Add to Player 2's store

        game.setBoard(board); // Update the game object with the final board state
        logger.info("Remaining stones collected. Final Board: {}", Arrays.toString(game.getBoard()));
    }

    /**
     * Determines the winner of the game based on the final scores in the stores.
     * Sets the winner in the MancalaGame instance.
     * @param game The MancalaGame instance to update.
     */
    private void determineWinner(MancalaGame game) {
        int[] board = game.getBoard(); // Get the latest board state.
        int player1Score = board[MancalaGame.PLAYER1_STORE];
        int player2Score = board[MancalaGame.PLAYER2_STORE];

        if (player1Score > player2Score) {
            game.setWinner(0); // Player 1 wins
        } else if (player2Score > player1Score) {
            game.setWinner(1); // Player 2 wins
        } else {
            game.setWinner(-1); // It's a draw
        }
        logger.info("Winner determined: Player {}", game.getWinner() == -1 ? "draw" : (game.getWinner() + 1));
    }
}
