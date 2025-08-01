package org.mancalgame.mancalagame.online;

import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.mancalgame.mancalagame.controller.online.OnlineGameController;
import org.mancalgame.mancalagame.game.MancalaGame.GameStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OnlineGameManager {

    private static final Logger logger = LoggerFactory.getLogger(OnlineGameManager.class);
    private static final long STALE_GAME_TIMEOUT = 600000; // 10 minutes

    private final Map<String, OnlineMancalaGame> activeGames = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToGameMap = new ConcurrentHashMap<>();

    private final MancalaGameService mancalaGameService;
    private final SimpMessagingTemplate messagingTemplate;

    public OnlineGameManager(MancalaGameService mancalaGameService, SimpMessagingTemplate messagingTemplate) {
        this.mancalaGameService = mancalaGameService;
        this.messagingTemplate = messagingTemplate;
    }

    public OnlineMancalaGame createGame() {
        OnlineMancalaGame newGame = new OnlineMancalaGame(mancalaGameService);
        activeGames.put(newGame.getGameId(), newGame);
        logger.info("Online game created with ID: {}", newGame.getGameId());
        return newGame;
    }

    // A helper method that combines creating a game and adding the host
    public OnlineMancalaGame createAndAddPlayer(String sessionId) {
        OnlineMancalaGame newGame = createGame();
        addPlayerToGame(newGame.getGameId(), sessionId);
        return newGame;
    }

    public Optional<OnlineMancalaGame> getGame(String gameId) {
        return Optional.ofNullable(activeGames.get(gameId));
    }

    public Optional<OnlineMancalaGame> addPlayerToGame(String gameId, String sessionId) {
        return getGame(gameId).map(game -> {
            synchronized (game) {
                int playerRole = game.addPlayer(sessionId);
                if (playerRole != -1) {
                    sessionToGameMap.put(sessionId, gameId);
                    logger.info("Player {} (session {}) joined game {}", playerRole + 1, sessionId, gameId);
                    return game;
                }
                logger.warn("Failed to add player {} to game {}: game is full or player already joined.", sessionId, gameId);
                return null;
            }
        });
    }

    public void removePlayer(String sessionId) {
        String gameId = sessionToGameMap.remove(sessionId);
        if (gameId != null) {
            getGame(gameId).ifPresent(game -> {
                synchronized (game) {
                    boolean playerWasInGame = game.removePlayer(sessionId);
                    if (playerWasInGame && game.getStatus() == GameStatus.CANCELLED) {

                        // THIS IS THE CORRECTED LINE
                        // We now create the DTO using the new constructor in the controller
                        OnlineGameController.GameStateDTO cancelledState = new OnlineGameController.GameStateDTO(game);
                        messagingTemplate.convertAndSend("/topic/game/" + gameId, cancelledState);

                        String remainingSessionId = game.getPlayer1SessionId() != null ? game.getPlayer1SessionId() : game.getPlayer2SessionId();
                        if (remainingSessionId != null) {
                            sessionToGameMap.remove(remainingSessionId);
                        }

                        activeGames.remove(gameId);
                        logger.info("Game {} removed due to cancellation.", gameId);
                    }
                }
            });
        }
    }

    public int getPlayerRoleInGame(String gameId, String sessionId) {
        return getGame(gameId).map(game -> {
            if (Objects.equals(game.getPlayer1SessionId(), sessionId)) return 0;
            if (Objects.equals(game.getPlayer2SessionId(), sessionId)) return 1;
            return -1;
        }).orElse(-1);
    }

    @Scheduled(fixedRate = STALE_GAME_TIMEOUT)
    public void cleanupStaleGames() {
        activeGames.entrySet().removeIf(entry -> {
            OnlineMancalaGame game = entry.getValue();
            if (game.getStatus() == GameStatus.WAITING_FOR_PLAYER && (System.currentTimeMillis() - game.getCreationTime()) > STALE_GAME_TIMEOUT) {
                logger.info("Removing stale game: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}