package org.mancalgame.mancalagame.online;

import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.mancalgame.mancalagame.game.MancalaGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents an individual online Mancala game instance, extending the core game logic
 * with network-specific attributes like player session IDs and overall game status.
 */
public class OnlineMancalaGame {

    private static final Logger logger = LoggerFactory.getLogger(OnlineMancalaGame.class);

    private final String gameId;
    private MancalaGame mancalaGame; // Made non-final to allow for reset
    private final MancalaGameService gameService;

    private String player1SessionId;
    private String player2SessionId;
    private MancalaGame.GameStatus status;
    private final long creationTime;

    // --- NEW: Fields to track rematch status ---
    private boolean player1WantsRematch = false;
    private boolean player2WantsRematch = false;


    public OnlineMancalaGame(MancalaGameService gameService) {
        this.gameId = UUID.randomUUID().toString();
        this.mancalaGame = new MancalaGame();
        this.gameService = gameService;
        this.status = MancalaGame.GameStatus.WAITING_FOR_PLAYER;
        this.creationTime = System.currentTimeMillis();
        logger.info("OnlineMancalaGame {} created with status: {}", gameId, status);
    }

    // --- NEW: Handle a player's rematch request ---
    public synchronized void setPlayerWantsRematch(int playerRole) {
        if (playerRole == 0) {
            this.player1WantsRematch = true;
            logger.info("Player 1 has requested a rematch in game [{}].", gameId);
        } else if (playerRole == 1) {
            this.player2WantsRematch = true;
            logger.info("Player 2 has requested a rematch in game [{}].", gameId);
        }
    }

    // --- NEW: Check if both players are ready ---
    public boolean bothPlayersWantRematch() {
        return this.player1WantsRematch && this.player2WantsRematch;
    }

    // --- NEW: Reset the game for a new match ---
    public synchronized void resetForRematch() {
        this.mancalaGame = new MancalaGame(); // Re-initialize the core game
        this.status = MancalaGame.GameStatus.IN_PROGRESS;
        this.player1WantsRematch = false;
        this.player2WantsRematch = false;
        logger.info("Game [{}] has been reset for a rematch.", gameId);
    }


    public synchronized int addPlayer(String sessionId) {
        if (Objects.equals(this.player1SessionId, sessionId) || Objects.equals(this.player2SessionId, sessionId)) {
            logger.warn("Session {} already present in game {}.", sessionId, gameId);
            return -1;
        }

        if (this.player1SessionId == null) {
            this.player1SessionId = sessionId;
            logger.info("Player 1 (Host) joined game {} with session ID: {}.", gameId, sessionId);
            return 0;
        } else if (this.player2SessionId == null) {
            this.player2SessionId = sessionId;
            this.status = MancalaGame.GameStatus.IN_PROGRESS;
            logger.info("Player 2 (Joiner) joined game {}. Status changed to IN_PROGRESS.", gameId);
            return 1;
        }
        logger.warn("Failed to add player {} to game {}: game full.", sessionId, gameId);
        return -1;
    }

    public synchronized boolean removePlayer(String sessionId) {
        if (Objects.equals(this.player1SessionId, sessionId)) {
            this.player1SessionId = null;
            this.status = MancalaGame.GameStatus.CANCELLED;
            logger.info("Player 1 (session {}) left game {}. Game cancelled.", sessionId, gameId);
            return true;
        } else if (Objects.equals(this.player2SessionId, sessionId)) {
            this.player2SessionId = null;
            this.status = MancalaGame.GameStatus.CANCELLED;
            logger.info("Player 2 (session {}) left game {}. Game cancelled.", sessionId, gameId);
            return true;
        }
        return false;
    }

    public synchronized boolean makeMove(int pitIndex, int currentPlayerRole) {
        if (mancalaGame.isGameOver() || this.status != MancalaGame.GameStatus.IN_PROGRESS) {
            return false;
        }

        if (mancalaGame.getCurrentPlayer() != currentPlayerRole) {
            return false;
        }

        boolean success = gameService.makeMove(this.mancalaGame, pitIndex);

        if (success && mancalaGame.isGameOver()) {
            this.status = MancalaGame.GameStatus.FINISHED;
            logger.info("Game {} finished after move.", gameId);
        }
        return success;
    }

    // --- Getters ---
    public String getGameId() { return gameId; }
    public MancalaGame getMancalaGame() { return mancalaGame; }
    public String getPlayer1SessionId() { return player1SessionId; }
    public String getPlayer2SessionId() { return player2SessionId; }
    public MancalaGame.GameStatus getStatus() { return status; }
    public long getCreationTime() { return creationTime; }
    public boolean isPlayer1WantsRematch() { return player1WantsRematch; }
    public boolean isPlayer2WantsRematch() { return player2WantsRematch; }
}