package org.mancalgame.mancalagame.online;

import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// NEW IMPORT: Import the GameStatus enum directly from MancalaGame
import org.mancalgame.mancalagame.game.MancalaGame.GameStatus;


/**
 * Manages all active online Mancala game instances.
 * Handles creation, retrieval, player addition/removal, and cleanup of games.
 */
@Service
public class OnlineGameManager {

    private static final Logger logger = LoggerFactory.getLogger(OnlineGameManager.class);
    // Timeout for stale games (e.g., games waiting for a second player for too long)
    private static final long STALE_GAME_TIMEOUT = 600000; // 10 minutes in milliseconds

    // Stores active game instances by game ID
    private final Map<String, OnlineMancalaGame> activeGames = new ConcurrentHashMap<>();
    // Maps WebSocket session IDs to the game ID they are currently participating in
    public final Map<String, String> sessionToGameMap = new ConcurrentHashMap<>();

    private final MancalaGameService mancalaGameService;
    private final SimpMessagingTemplate messagingTemplate; // Used to send messages to WebSocket clients

    public OnlineGameManager(MancalaGameService mancalaGameService, SimpMessagingTemplate messagingTemplate) {
        this.mancalaGameService = mancalaGameService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Creates a new OnlineMancalaGame instance and registers it.
     * @return The newly created OnlineMancalaGame.
     */
    public OnlineMancalaGame createGame() {
        OnlineMancalaGame newGame = new OnlineMancalaGame(mancalaGameService);
        activeGames.put(newGame.getGameId(), newGame);
        logger.info("Online game created with ID: {}", newGame.getGameId());
        return newGame;
    }

    /**
     * Retrieves an active game by its ID.
     * @param gameId The ID of the game to retrieve.
     * @return An Optional containing the OnlineMancalaGame if found, or empty if not.
     */
    public Optional<OnlineMancalaGame> getGame(String gameId) {
        return Optional.ofNullable(activeGames.get(gameId));
    }

    /**
     * Adds a player (identified by session ID) to a specified game.
     * Handles assigning player roles (Player 1 or Player 2).
     * @param gameId The ID of the game to join.
     * @param sessionId The WebSocket session ID of the player.
     * @return An Optional containing the updated OnlineMancalaGame if the player was added, or empty if the game is full/not found.
     */
    public Optional<OnlineMancalaGame> addPlayerToGame(String gameId, String sessionId) {
        return getGame(gameId).map(game -> {
            // Synchronize on the specific game instance to prevent race conditions during player addition
            synchronized (game) {
                // Now directly using the imported GameStatus enum
                if (game.getStatus() == GameStatus.WAITING_FOR_PLAYER ||
                        (game.getStatus() == GameStatus.IN_PROGRESS &&
                                (game.getPlayer1SessionId() == null || game.getPlayer2SessionId() == null))) {

                    int playerRole = game.addPlayer(sessionId);
                    if (playerRole != -1) { // If player was successfully added
                        sessionToGameMap.put(sessionId, gameId); // Map session to game
                        logger.info("Player {} (session {}) joined game {}", playerRole + 1, sessionId, gameId);
                        return game;
                    }
                }
                logger.warn("Failed to add player {} to game {}: game not found or full", sessionId, gameId);
                return null; // Player could not be added
            }
        });
    }

    /**
     * Removes a player from their associated game when their WebSocket session disconnects.
     * Handles game cancellation if an opponent disconnects.
     * @param sessionId The WebSocket session ID of the player to remove.
     * @return The game ID from which the player was removed, or null if not found in any game.
     */
    public String removePlayer(String sessionId) {
        String gameId = sessionToGameMap.remove(sessionId); // Remove session from map
        if (gameId != null) {
            getGame(gameId).ifPresent(game -> {
                // Synchronize on the game to safely remove player and update game status
                synchronized (game) {
                    game.removePlayer(sessionId); // Update game state (e.g., set to CANCELLED)

                    String remainingSessionId = null;
                    if (game.getPlayer1SessionId() != null) {
                        remainingSessionId = game.getPlayer1SessionId();
                    } else if (game.getPlayer2SessionId() != null) {
                        remainingSessionId = game.getPlayer2SessionId();
                    }

                    // Now directly using the imported GameStatus enum
                    if (game.getStatus() == GameStatus.CANCELLED) {
                        // If game was cancelled (e.g., by player leaving), remove it from active games
                        activeGames.remove(gameId);
                        // Notify all subscribers to this game's topic that it's cancelled
                        messagingTemplate.convertAndSend("/topic/game/" + gameId, game.toDTO());

                        // Send a specific error message to the remaining player if any
                        if (remainingSessionId != null) {
                            messagingTemplate.convertAndSendToUser(remainingSessionId, "/queue/errors",
                                    "Game cancelled: Opponent disconnected.");
                        }
                        logger.info("Game {} removed due to cancellation (player disconnected)", gameId);
                    } else if (remainingSessionId != null) {
                        // If game is not cancelled but one player left (e.g., in progress, other player still there)
                        // This might indicate a pause or waiting for a new player (depends on specific rules)
                        // For simplicity, we just notify the remaining player.
                        messagingTemplate.convertAndSendToUser(remainingSessionId, "/queue/errors",
                                "Opponent disconnected. Game paused or waiting for new player.");
                        logger.info("Player {} disconnected from game {}. Game status: {}", sessionId, gameId, game.getStatus());
                    } else {
                        // No players left in the game, remove it completely
                        activeGames.remove(gameId);
                        logger.info("Game {} removed as all players disconnected.", gameId);
                    }
                }
            });
            logger.info("Session {} removed from game {}", sessionId, gameId);
        } else {
            logger.info("Session {} not found in any active game (no associated gameId)", sessionId);
        }
        return gameId;
    }

    /**
     * Finds an OnlineMancalaGame instance by a player's session ID.
     * @param sessionId The WebSocket session ID.
     * @return An Optional containing the game if found, or empty.
     */
    public Optional<OnlineMancalaGame> findGameBySessionId(String sessionId) {
        return Optional.ofNullable(sessionToGameMap.get(sessionId)).flatMap(this::getGame);
    }

    /**
     * Determines the player role (0 or 1) of a given session in a specific game.
     * @param gameId The ID of the game.
     * @param sessionId The WebSocket session ID.
     * @return 0 for Player 1, 1 for Player 2, or -1 if the session is not in the game.
     */
    public int getPlayerRoleInGame(String gameId, String sessionId) {
        return getGame(gameId)
                .map(game -> {
                    if (Objects.equals(game.getPlayer1SessionId(), sessionId)) return 0;
                    if (Objects.equals(game.getPlayer2SessionId(), sessionId)) return 1;
                    return -1; // Session not found in this game
                })
                .orElse(-1); // Game not found
    }

    /**
     * Scheduled task to clean up stale games (e.g., games that were created but never joined).
     * Runs periodically based on fixedRate.
     */
    @Scheduled(fixedRate = STALE_GAME_TIMEOUT) // Runs every STALE_GAME_TIMEOUT milliseconds
    public void cleanupStaleGames() {
        long currentTime = System.currentTimeMillis();
        // Now directly using the imported GameStatus enum
        activeGames.entrySet().removeIf(entry -> {
            OnlineMancalaGame game = entry.getValue();
            if (game.getStatus() == GameStatus.WAITING_FOR_PLAYER &&
                    (currentTime - game.getCreationTime()) > STALE_GAME_TIMEOUT) {
                logger.info("Removing stale game: {}", entry.getKey());
                return true; // Mark for removal
            }
            return false; // Keep the game
        });
        logger.debug("Stale game cleanup performed. Active games remaining: {}", activeGames.size());
    }
}
