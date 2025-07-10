package org.mancalgame.mancalagame.controller.online;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper; // Import ObjectMapper
import org.mancalgame.mancalagame.online.OnlineGameManager;
import org.mancalgame.mancalagame.online.OnlineMancalaGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.mancalgame.mancalagame.game.MancalaGame;

/**
 * Handles WebSocket (STOMP) messages for online Mancala game play.
 * Manages game hosting, joining, making moves, and player connection/disconnection events.
 */
@Controller
public class OnlineGameController {

    private static final Logger logger = LoggerFactory.getLogger(OnlineGameController.class);

    private final OnlineGameManager gameManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper; // Inject ObjectMapper

    public OnlineGameController(OnlineGameManager gameManager, SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.gameManager = gameManager;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper; // Initialize ObjectMapper
    }

    /**
     * DTO representing the full state of the Mancala game for frontend consumption.
     * This class will be serialized to JSON.
     * Board is `List<Integer>` for frontend, converted from `int[]` on creation.
     */
    public static class GameStateDTO {
        private String gameId;
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        private List<Integer> board;
        private int currentPlayer;
        private boolean gameOver;
        private int winner;
        private String gameStatus;

        public GameStateDTO() {}

        public GameStateDTO(String gameId, int[] board, int currentPlayer, boolean gameOver, int winner, String gameStatus) {
            this.gameId = gameId;
            this.board = (board != null) ? Arrays.stream(board).boxed().toList() : null;
            this.currentPlayer = currentPlayer;
            this.gameOver = gameOver;
            this.winner = winner;
            this.gameStatus = gameStatus;
        }

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public List<Integer> getBoard() { return board; }
        public void setBoard(List<Integer> board) { this.board = board; }
        public int getCurrentPlayer() { return currentPlayer; }
        public void setCurrentPlayer(int currentPlayer) { this.currentPlayer = currentPlayer; }
        public boolean isGameOver() { return gameOver; }
        public void setGameOver(boolean gameOver) { this.gameOver = gameOver; }
        public int getWinner() { return winner; }
        public void setWinner(int winner) { this.winner = winner; }
        public String getGameStatus() { return gameStatus; }
        public void setGameStatus(String gameStatus) { this.gameStatus = gameStatus; }
    }

    /**
     * DTO for sending initial game details to a specific client.
     * Extends GameStateDTO and adds the assigned player role for the recipient.
     */
    public static class InitialGameDetailsDTO extends GameStateDTO {
        private int assignedPlayerRole;

        public InitialGameDetailsDTO() {
            super();
        }

        public InitialGameDetailsDTO(String gameId, int[] board, int currentPlayer, boolean gameOver, int winner, String gameStatus, int assignedPlayerRole) {
            super(gameId, board, currentPlayer, gameOver, winner, gameStatus);
            this.assignedPlayerRole = assignedPlayerRole;
        }

        public int getAssignedPlayerRole() { return assignedPlayerRole; }
        public void setAssignedPlayerRole(int assignedPlayerRole) { this.assignedPlayerRole = assignedPlayerRole; }
    }


    /**
     * Handles a request from a client to host a new online game.
     * @param headerAccessor Provides access to session ID.
     * @param principal Provides user identification for messaging.
     */
    @MessageMapping("/game.host")
    public void hostGame(SimpMessageHeaderAccessor headerAccessor, Principal principal) {
        String sessionId = headerAccessor.getSessionId();
        String userToSendTo = (principal != null && principal.getName() != null) ? principal.getName() : sessionId;

        if (sessionId == null) {
            logger.error("Session ID is null for hostGame request. Cannot process.");
            return;
        }
        logger.info("Session [{}] requested to host a new game. Principal: {}", sessionId, (principal != null ? principal.getName() : "null"));

        try {
            OnlineMancalaGame newGame = gameManager.createGame();
            Optional<OnlineMancalaGame> gameOptional = gameManager.addPlayerToGame(newGame.getGameId(), sessionId);

            if (gameOptional.isEmpty()) {
                logger.error("Failed to host game: Could not create or add host player for session [{}].", sessionId);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Failed to host game: Could not create or join. Please try again.");
                return;
            }

            OnlineMancalaGame game = gameOptional.get();

            InitialGameDetailsDTO hostDetails = new InitialGameDetailsDTO(
                    game.getGameId(),
                    game.getMancalaGame().getBoard(),
                    game.getMancalaGame().getCurrentPlayer(),
                    game.getMancalaGame().isGameOver(),
                    game.getMancalaGame().getWinner(),
                    game.getStatus().toString(),
                    0
            );

            // Log the DTO as a JSON string right before sending
            String jsonPayload = objectMapper.writeValueAsString(hostDetails);
            logger.info("Sending initial game details JSON payload to host (user/session {}): {}", userToSendTo, jsonPayload);

            messagingTemplate.convertAndSendToUser("absc", "/queue/game.details", hostDetails);
            logger.info("Session [{}] successfully hosted game [{}]. Sent initial details. {}", sessionId, game.getGameId() , userToSendTo);

        } catch (Exception e) {
            logger.error("Error while hosting game for session [{}]: {}", sessionId, e.getMessage(), e);
            messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Internal server error while hosting game: " + e.getMessage());
        }
    }

    /**
     * Handles a request from a client to join an existing online game.
     * @param joinRequest DTO containing the game ID to join.
     * @param headerAccessor Provides access to session ID.
     * @param principal Provides user identification for messaging.
     */
    @MessageMapping("/game.join")
    public void joinGame(@Payload JoinGameRequest joinRequest, SimpMessageHeaderAccessor headerAccessor, Principal principal) {
        String sessionId = headerAccessor.getSessionId();
        String gameId = joinRequest.getGameId();
        String userToSendTo = (principal != null && principal.getName() != null) ? principal.getName() : sessionId;

        if (sessionId == null) {
            logger.error("Session ID is null for joinGame request for game [{}]. Cannot process.", gameId);
            return;
        }
        logger.info("Session [{}] attempting to join game [{}]. Principal: {}", sessionId, gameId, (principal != null ? principal.getName() : "null"));

        try {
            Optional<OnlineMancalaGame> gameOptional = gameManager.addPlayerToGame(gameId, sessionId);

            if (gameOptional.isEmpty()) {
                logger.warn("Session [{}] failed to join game [{}]: Game not found, is full, or already started.", sessionId, gameId);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Game not found, is full, or already started.");
                return;
            }

            OnlineMancalaGame game = gameOptional.get();

            synchronized (game) {
                InitialGameDetailsDTO joinerDetails = new InitialGameDetailsDTO(
                        game.getGameId(),
                        game.getMancalaGame().getBoard(),
                        game.getMancalaGame().getCurrentPlayer(),
                        game.getMancalaGame().isGameOver(),
                        game.getMancalaGame().getWinner(),
                        game.getStatus().toString(),
                        1
                );
                // Log the DTO as a JSON string right before sending
                String jsonJoinerPayload = objectMapper.writeValueAsString(joinerDetails);
                logger.info("Sending initial game details JSON payload to joiner (user/session {}): {}", userToSendTo, jsonJoinerPayload);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/game.details", joinerDetails);

                GameStateDTO gameStateUpdate = new GameStateDTO(
                        game.getGameId(),
                        game.getMancalaGame().getBoard(),
                        game.getMancalaGame().getCurrentPlayer(),
                        game.getMancalaGame().isGameOver(),
                        game.getMancalaGame().getWinner(),
                        game.getStatus().toString()
                );
                // Log the DTO as a JSON string right before broadcasting
                String jsonBroadcastPayload = objectMapper.writeValueAsString(gameStateUpdate);
                logger.info("Broadcasting game state update JSON payload for game {}: {}", gameId, jsonBroadcastPayload);
                messagingTemplate.convertAndSend("/topic/game/" + gameId, gameStateUpdate);
                logger.info("Session [{}] successfully joined game [{}]. Broadcasted game state.", sessionId, gameId);
            }
        } catch (Exception e) {
            logger.error("Error joining game [{}] for session [{}]: {}", gameId, sessionId, e.getMessage(), e);
            messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Internal server error while joining game: " + e.getMessage());
        }
    }

    /**
     * Handles a player's move request in a specific online game.
     * @param gameId The ID of the game where the move is being made.
     * @param moveRequest DTO containing the pit index for the move.
     * @param headerAccessor Provides access to session ID to identify the player.
     * @param principal Provides user identification for messaging.
     */
    @MessageMapping("/game.{gameId}.move")
    public void makeMove(@DestinationVariable String gameId,
                         @Payload MoveRequest moveRequest,
                         SimpMessageHeaderAccessor headerAccessor,
                         Principal principal) {

        String sessionId = headerAccessor.getSessionId();
        int pitIndex = moveRequest.getPitIndex();
        String userToSendTo = (principal != null && principal.getName() != null) ? principal.getName() : sessionId;

        if (sessionId == null) {
            logger.error("Session ID is null for move request in game [{}]. Cannot process.", gameId);
            return;
        }
        logger.info("Session [{}] requested move in game [{}], pit index: {}", sessionId, gameId, pitIndex);

        Optional<OnlineMancalaGame> optionalGame = gameManager.getGame(gameId);

        if (optionalGame.isEmpty()) {
            logger.warn("Move request from session [{}] for non-existent or ended game [{}].", sessionId, gameId);
            messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Game not found or has ended.");
            return;
        }

        OnlineMancalaGame game = optionalGame.get();

        synchronized (game) {
            if (game.getStatus() != MancalaGame.GameStatus.IN_PROGRESS) {
                logger.warn("Move attempt in game [{}] when not in progress. Status: {}. Session: {}", gameId, game.getStatus(), sessionId);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Game is not in progress. Current status: " + game.getStatus());
                return;
            }

            int role = gameManager.getPlayerRoleInGame(gameId, sessionId);

            if (role == -1) {
                logger.warn("Session [{}] tried to move in game [{}] but is not a player.", sessionId, gameId);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "You are not an active player in this game.");
                return;
            }

            try {
                boolean moveSuccessful = game.makeMove(pitIndex, role);
                if (moveSuccessful) {
                    GameStateDTO gameStateUpdate = new GameStateDTO(
                            game.getGameId(),
                            game.getMancalaGame().getBoard(),
                            game.getMancalaGame().getCurrentPlayer(),
                            game.getMancalaGame().isGameOver(),
                            game.getMancalaGame().getWinner(),
                            game.getStatus().toString()
                    );
                    // Log the DTO as a JSON string right before broadcasting
                    String jsonBroadcastPayload = objectMapper.writeValueAsString(gameStateUpdate);
                    logger.debug("Broadcasting game state update JSON payload for game {}: {}", gameId, jsonBroadcastPayload);
                    messagingTemplate.convertAndSend("/topic/game/" + gameId, gameStateUpdate);
                    logger.info("Move successful for Player {} in game [{}]. Board updated.", role + 1, gameId);
                } else {
                    logger.warn("Move returned false for Player {} in game [{}], pit {}. Possible internal error.", role + 1, gameId, pitIndex);
                    messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Invalid move or not your turn. (No specific error reason from server)");
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid move exception in game [{}], session [{}]: {}", gameId, sessionId, e.getMessage());
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Invalid move: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Error processing move for session [{}] in game [{}]: {}", sessionId, gameId, e.getMessage(), e);
                messagingTemplate.convertAndSendToUser(userToSendTo, "/queue/errors", "Internal server error during move: " + e.getMessage());
            }
        }
    }

    /**
     * Event listener for new WebSocket connections.
     * Logs the connection.
     * @param event The SessionConnectedEvent.
     */
    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String userName = Optional.ofNullable(event.getUser()).map(Principal::getName).orElse("unknown");

        if (sessionId != null) {
            logger.info("WebSocket connection established for session: {}, User: {}", sessionId, userName);
        }
    }

    /**
     * Event listener for WebSocket disconnections.
     * Removes the player from any active game they were in.
     * @param event The SessionDisconnectEvent.
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String userName = Optional.ofNullable(event.getUser()).map(Principal::getName).orElse("unknown");

        if (sessionId != null) {
            logger.info("WebSocket disconnected for session: {}, User: {}", sessionId, userName);
            gameManager.removePlayer(sessionId);
        }
    }

    // --- DTO classes for incoming messages (payloads from client) ---

    public static class JoinGameRequest {
        private String gameId;
        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
    }

    public static class MoveRequest {
        private int pitIndex;
        public int getPitIndex() { return pitIndex; }
        public void setPitIndex(int pitIndex) { this.pitIndex = pitIndex; }
    }
}
