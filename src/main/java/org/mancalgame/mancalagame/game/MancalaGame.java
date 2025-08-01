package org.mancalgame.mancalagame.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Represents the core state and rules of a single Mancala game instance.
 * This class holds the board, current player, and game status.
 * It's designed to be independent of session management or network specifics.
 */
public class MancalaGame implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(MancalaGame.class);
    private static final long serialVersionUID = 1L;

    // The game board, 14 pits in total:
    // Pits 0-5: Player 1's regular pits
    // Pit 6: Player 1's store (Mancala)
    // Pits 7-12: Player 2's regular pits
    // Pit 13: Player 2's store (Mancala)
    private int[] board;
    private int currentPlayer; // 0 for Player 1, 1 for Player 2
    private boolean gameOver;
    private int winner; // -1 for draw or game ongoing, 0 for Player 1, 1 for Player 2

    // Constants for board indices for clarity
    public static final int PLAYER1_PIT_START = 0;
    public static final int PLAYER1_PIT_END = 5;
    public static final int PLAYER1_STORE = 6;
    public static final int PLAYER2_PIT_START = 7;
    public static final int PLAYER2_PIT_END = 12;
    public static final int PLAYER2_STORE = 13;

    /**
     * Constructs a new MancalaGame with the standard initial board setup.
     */
    public MancalaGame() {
        this.board = new int[14];
        // Initialize all regular pits with 4 stones
        for (int i = PLAYER1_PIT_START; i <= PLAYER1_PIT_END; i++) {
            this.board[i] = 4;
        }
        for (int i = PLAYER2_PIT_START; i <= PLAYER2_PIT_END; i++) {
            this.board[i] = 4;
        }
        // Initialize stores with 0 stones
        this.board[PLAYER1_STORE] = 0;
        this.board[PLAYER2_STORE] = 0;

        this.currentPlayer = 0; // Player 1 (index 0) typically starts
        this.gameOver = false;
        this.winner = -1; // No winner initially, or indicates a draw if game ends this way
        logger.info("New MancalaGame initialized: board={}", Arrays.toString(board));
    }
    // Add this new method inside your MancalaGame.java class

    public void reset() {
        this.board = new int[14];
        for (int i = PLAYER1_PIT_START; i <= PLAYER1_PIT_END; i++) {
            this.board[i] = 4;
        }
        for (int i = PLAYER2_PIT_START; i <= PLAYER2_PIT_END; i++) {
            this.board[i] = 4;
        }
        this.board[PLAYER1_STORE] = 0;
        this.board[PLAYER2_STORE] = 0;

        this.currentPlayer = 0;
        this.gameOver = false;
        this.winner = -1;
        logger.info("MancalaGame has been reset to its initial state.");
    }

    // --- Getters and Setters ---

    /**
     * Returns a defensive copy of the current game board.
     * @return An array representing the current state of all pits and stores.
     */
    public int[] getBoard() {
        return Arrays.copyOf(board, board.length);
    }

    /**
     * Sets the game board to a new state. Performs validation to ensure a valid board.
     * @param board The new board array (must be length 14, no negative stones).
     * @throws IllegalArgumentException if the board is null, incorrect length, or contains negative stones.
     */
    public void setBoard(int[] board) {
        if (board == null || board.length != 14) {
            logger.error("Invalid board: must be non-null and length 14");
            throw new IllegalArgumentException("Board must be non-null and length 14");
        }
        for (int stones : board) {
            if (stones < 0) {
                logger.error("Invalid board: negative stones detected");
                throw new IllegalArgumentException("Board cannot contain negative stones");
            }
        }
        this.board = Arrays.copyOf(board, board.length); // Make a defensive copy when setting
        logger.debug("Board updated: {}", Arrays.toString(this.board));
    }

    public int getCurrentPlayer() {
        return currentPlayer;
    }

    /**
     * Sets the current player.
     * @param currentPlayer The player whose turn it is (0 or 1).
     * @throws IllegalArgumentException if the player is not 0 or 1.
     */
    public void setCurrentPlayer(int currentPlayer) {
        if (currentPlayer != 0 && currentPlayer != 1) {
            logger.error("Invalid currentPlayer: {}", currentPlayer);
            throw new IllegalArgumentException("Current player must be 0 or 1");
        }
        this.currentPlayer = currentPlayer;
        logger.debug("Current player set to: {}", currentPlayer);
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
        logger.debug("Game over set to: {}", gameOver);
    }

    public int getWinner() {
        return winner;
    }

    /**
     * Sets the winner of the game.
     * @param winner The winner (-1 for draw/ongoing, 0 for Player 1, 1 for Player 2).
     * @throws IllegalArgumentException if the winner value is invalid.
     */
    public void setWinner(int winner) {
        if (winner != -1 && winner != 0 && winner != 1) {
            logger.error("Invalid winner: {}", winner);
            throw new IllegalArgumentException("Winner must be -1, 0, or 1");
        }
        this.winner = winner;
        logger.debug("Winner set to: {}", winner);
    }

    /**
     * Gets the number of stones in a specific pit.
     * @param pitIndex The index of the pit.
     * @return The number of stones.
     * @throws IllegalArgumentException if the pit index is out of bounds.
     */
    public int getStonesInPit(int pitIndex) {
        if (pitIndex >= 0 && pitIndex < board.length) {
            return board[pitIndex];
        }
        logger.error("Invalid pit index: {}", pitIndex);
        throw new IllegalArgumentException("Invalid pit index: " + pitIndex);
    }

    /**
     * Data Transfer Object (DTO) for conveying the game state to clients.
     * This class is public and static so it can be extended by other DTOs.
     */
    public static class GameStateDTO implements Serializable {
        private String gameId;
        private int[] board;
        private int currentPlayer;
        private boolean gameOver;
        private int winner; // -1 for draw, 0 for Player 1, 1 for Player 2
        private String gameStatus; // e.g., WAITING_FOR_PLAYER, IN_PROGRESS, FINISHED, CANCELLED

        // Default constructor for Jackson deserialization
        public GameStateDTO() {
        }

        public GameStateDTO(String gameId, int[] board, int currentPlayer, boolean gameOver, int winner, String gameStatus) {
            this.gameId = gameId;
            // Defensive copy for the DTO to ensure its independence
            this.board = (board != null) ? Arrays.copyOf(board, board.length) : null;
            this.currentPlayer = currentPlayer;
            this.gameOver = gameOver;
            this.winner = winner;
            this.gameStatus = gameStatus;
        }

        // Getters (used by Jackson for serialization)
        public String getGameId() { return gameId; }
        public int[] getBoard() { return (board != null) ? Arrays.copyOf(board, board.length) : null; } // Defensive copy
        public int getCurrentPlayer() { return currentPlayer; }
        public boolean isGameOver() { return gameOver; }
        public int getWinner() { return winner; }
        public String getGameStatus() { return gameStatus; }

        // Setters (useful if you ever need to deserialize this DTO from client, otherwise optional)
        public void setGameId(String gameId) { this.gameId = gameId; }
        public void setBoard(int[] board) { this.board = (board != null) ? Arrays.copyOf(board, board.length) : null; }
        public void setCurrentPlayer(int currentPlayer) { this.currentPlayer = currentPlayer; }
        public void setGameOver(boolean gameOver) { this.gameOver = gameOver; }
        public void setWinner(int winner) { this.winner = winner; }
        public void setGameStatus(String gameStatus) { this.gameStatus = gameStatus; }

        @Override
        public String toString() {
            return "GameStateDTO{" +
                    "gameId='" + gameId + '\'' +
                    ", board=" + Arrays.toString(board) +
                    ", currentPlayer=" + currentPlayer +
                    ", gameOver=" + gameOver +
                    ", winner=" + winner +
                    ", gameStatus='" + gameStatus + '\'' +
                    '}';
        }
    }

    /**
     * Enum for the overall status of an OnlineMancalaGame instance.
     * This is separate from the internal gameOver status of MancalaGame.
     */
    public enum GameStatus {
        WAITING_FOR_PLAYER, // Game created, waiting for second player
        IN_PROGRESS,        // Both players joined, game active
        FINISHED,           // Game logic reached a terminal state (game over)
        CANCELLED           // Game ended due to player disconnection
    }
}
