package org.mancalgame.mancalagame.online;

import org.mancalgame.mancalagame.game.MancalaGame;
import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

// Import the specific GameStateDTO from your OnlineGameController
import org.mancalgame.mancalagame.controller.online.OnlineGameController;

/**
 * Represents an individual online Mancala game instance, extending the core game logic
 * with network-specific attributes like player session IDs and overall game status.
 */
public class OnlineMancalaGame {

    private static final Logger logger = LoggerFactory.getLogger(OnlineMancalaGame.class);

    // This line was potentially redundant; removed for clarity assuming 'private MancalaGame.GameStatus status;' is used.
    // public static MancalaGame.GameStatus GameStatus;


    private final String gameId; // Unique ID for this online game instance
    private final MancalaGame mancalaGame; // The underlying core game logic instance
    private final MancalaGameService gameService; // Service to perform game moves

    private String player1SessionId; // WebSocket session ID of Player 1 (host)
    private String player2SessionId; // WebSocket session ID of Player 2 (joiner)
    private MancalaGame.GameStatus status; // Current status of the online game (WAITING, IN_PROGRESS, FINISHED, CANCELLED)
    private final long creationTime; // Timestamp when the game was created, used for stale game cleanup

    /**
     * Constructs a new OnlineMancalaGame.
     * @param gameService The MancalaGameService to use for game logic operations.
     */
    public OnlineMancalaGame(MancalaGameService gameService) {
        this.gameId = UUID.randomUUID().toString(); // Generate a unique ID for the game
        this.mancalaGame = new MancalaGame(); // Initialize the core Mancala game
        this.gameService = gameService;
        this.status = MancalaGame.GameStatus.WAITING_FOR_PLAYER; // Initially waiting for players
        this.creationTime = System.currentTimeMillis(); // Record creation time
        logger.info("OnlineMancalaGame {} created with status: {}", gameId, status);
    }

    /**
     * Adds a player to this game instance.
     * @param sessionId The WebSocket session ID of the player to add.
     * @return 0 if added as Player 1, 1 if added as Player 2, or -1 if the game is full or session already present.
     */
    public synchronized int addPlayer(String sessionId) {
        if (Objects.equals(this.player1SessionId, sessionId) || Objects.equals(this.player2SessionId, sessionId)) {
            logger.warn("Session {} already present in game {}.", sessionId, gameId);
            return -1; // Session already in game
        }

        if (this.player1SessionId == null) {
            this.player1SessionId = sessionId;
            logger.info("Player 1 (Host) joined game {} with session ID: {}. Current status: {}", gameId, sessionId, status);
            return 0; // Player 1
        } else if (this.player2SessionId == null) {
            this.player2SessionId = sessionId;
            this.status = MancalaGame.GameStatus.IN_PROGRESS; // Game starts when second player joins
            logger.info("Player 2 (Joiner) joined game {}. Status changed to IN_PROGRESS. Players: {}, {}", gameId, player1SessionId, player2SessionId);
            return 1; // Player 2
        }
        logger.warn("Failed to add player {} to game {}: game full. Current status: {}", sessionId, gameId, status);
        return -1; // Game is full
    }

    /**
     * Removes a player from this game instance.
     * If a player leaves, the game status is typically set to CANCELLED.
     * @param sessionId The WebSocket session ID of the player to remove.
     * @return True if the player was successfully removed, false otherwise.
     */
    public synchronized boolean removePlayer(String sessionId) {
        if (Objects.equals(this.player1SessionId, sessionId)) {
            this.player1SessionId = null;
            this.status = MancalaGame.GameStatus.CANCELLED; // Game cancelled if a player leaves
            logger.info("Player 1 (session {}) left game {}. Game cancelled. Current status: {}", sessionId, gameId, status);
            return true;
        } else if (Objects.equals(this.player2SessionId, sessionId)) {
            this.player2SessionId = null;
            this.status = MancalaGame.GameStatus.CANCELLED; // Game cancelled if a player leaves
            logger.info("Player 2 (session {}) left game {}. Game cancelled. Current status: {}", sessionId, gameId, status);
            return true;
        }
        logger.warn("Failed to remove player {} from game {}: not found. Current status: {}", sessionId, gameId, status);
        return false;
    }

    /**
     * Delegates the move to the underlying MancalaGameService.
     * Updates the game status if the move leads to a game over condition.
     * @param pitIndex The index of the pit selected by the player.
     * @param currentPlayerRole The role of the player making the move (0 or 1).
     * @return True if the move was successfully executed, false otherwise (e.g., not current player's turn).
     */
    public synchronized boolean makeMove(int pitIndex, int currentPlayerRole) {
        if (mancalaGame.isGameOver()) {
            logger.debug("Move attempted on a finished game: {}. Current status: {}", gameId, status);
            return false; // Cannot make moves on a finished game
        }

        if (mancalaGame.getCurrentPlayer() != currentPlayerRole) {
            logger.debug("Player {} tried to move out of turn in game {}. Current turn: Player {}. Status: {}",
                    currentPlayerRole + 1, gameId, mancalaGame.getCurrentPlayer() + 1, status);
            return false; // Not this player's turn
        }

        // Execute the move using the game service
        boolean success = gameService.makeMove(this.mancalaGame, pitIndex);

        if (success && mancalaGame.isGameOver()) {
            this.status = MancalaGame.GameStatus.FINISHED; // Update online game status if core game ends
            logger.info("Game {} finished after move. Current status: {}", gameId, status);
        }
        return success;
    }

    // --- Getters for OnlineGameManager access ---
    public String getGameId() {
        return gameId;
    }

    public MancalaGame getMancalaGame() {
        return mancalaGame;
    }

    public String getPlayer1SessionId() {
        return player1SessionId;
    }

    public String getPlayer2SessionId() {
        return player2SessionId;
    }

    public MancalaGame.GameStatus getStatus() {
        return status;
    }

    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Checks if the game is ready to start (both players connected and status is IN_PROGRESS).
     * @return True if the game is ready, false otherwise.
     */
    public boolean isReadyToStart() {
        return player1SessionId != null && player2SessionId != null && status == MancalaGame.GameStatus.IN_PROGRESS;
    }

    /**
     * Converts the current state of the online game into a GameStateDTO for client consumption.
     * IMPORTANT: This now returns OnlineGameController.GameStateDTO
     * @return A GameStateDTO representing the current game state.
     */
    public OnlineGameController.GameStateDTO toDTO() {
        logger.debug("toDTO called for game {}. Status: {}. Board: {}", gameId, status, Arrays.toString(mancalaGame.getBoard()));
        // Use the GameStateDTO from OnlineGameController class
        return new OnlineGameController.GameStateDTO(
                gameId,
                mancalaGame.getBoard(), // This returns int[], which the OGC.GameStateDTO constructor handles
                mancalaGame.getCurrentPlayer(),
                mancalaGame.isGameOver(),
                mancalaGame.getWinner(),
                status.toString() // Convert enum to String for DTO
        );
    }
}
