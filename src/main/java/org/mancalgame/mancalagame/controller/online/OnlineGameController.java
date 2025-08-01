package org.mancalgame.mancalagame.controller.online;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mancalgame.mancalagame.online.OnlineGameManager;
import org.mancalgame.mancalagame.online.OnlineMancalaGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.mancalgame.mancalagame.game.MancalaGame;

@Controller
public class OnlineGameController {

    private static final Logger logger = LoggerFactory.getLogger(OnlineGameController.class);

    private final OnlineGameManager gameManager;
    private final SimpMessagingTemplate messagingTemplate;

    public OnlineGameController(OnlineGameManager gameManager, SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.gameManager = gameManager;
        this.messagingTemplate = messagingTemplate;
    }

    // DTOs for client-server communication
    public static class GameStateDTO {
        private String gameId;
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        private List<Integer> board;
        private int currentPlayer;
        private int winner;
        private boolean gameOver;
        private String gameStatus;

        public GameStateDTO() {}

        public GameStateDTO(String gameId, int[] board, int currentPlayer, boolean gameOver, int winner, String gameStatus) {
            this.gameId = gameId;
            this.board = (board != null) ? Arrays.stream(board).boxed().collect(Collectors.toList()) : null;
            this.currentPlayer = currentPlayer;
            this.gameOver = gameOver;
            this.winner = winner;
            this.gameStatus = gameStatus;
        }

        // Getters and Setters
        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public List<Integer> getBoard() { return board; }
        public void setBoard(List<Integer> board) { this.board = board; }
        public int getCurrentPlayer() { return currentPlayer; }
        public void setCurrentPlayer(int currentPlayer) { this.currentPlayer = currentPlayer; }
        public int getWinner() { return winner; }
        public void setWinner(int winner) { this.winner = winner; }
        public boolean isGameOver() { return gameOver; }
        public void setGameOver(boolean gameOver) { this.gameOver = gameOver; }
        public String getGameStatus() { return gameStatus; }
        public void setGameStatus(String gameStatus) { this.gameStatus = gameStatus; }
    }

    public static class InitialGameDetailsDTO extends GameStateDTO {
        private int assignedPlayerRole;

        public InitialGameDetailsDTO() { super(); }

        public InitialGameDetailsDTO(String gameId, int[] board, int currentPlayer, boolean gameOver, int winner, String gameStatus, int assignedPlayerRole) {
            super(gameId, board, currentPlayer, gameOver, winner, gameStatus);
            this.assignedPlayerRole = assignedPlayerRole;
        }

        public int getAssignedPlayerRole() { return assignedPlayerRole; }
        public void setAssignedPlayerRole(int assignedPlayerRole) { this.assignedPlayerRole = assignedPlayerRole; }
    }

    public record ErrorDTO(String message) {}

    @MessageMapping("/game.host")
    @SendToUser(destinations = "/queue/game.details", broadcast = false)
    public InitialGameDetailsDTO hostGame(SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        logger.info("Session [{}] requested to host a new game.", sessionId);

        OnlineMancalaGame newGame = gameManager.createGame();
        OnlineMancalaGame game = gameManager.addPlayerToGame(newGame.getGameId(), sessionId)
                .orElseThrow(() -> new RuntimeException("Failed to create and join game."));

        logger.info("Session [{}] successfully hosted game [{}].", sessionId, game.getGameId());
        return new InitialGameDetailsDTO(
                game.getGameId(),
                game.getMancalaGame().getBoard(),
                game.getMancalaGame().getCurrentPlayer(),
                game.getMancalaGame().isGameOver(),
                game.getMancalaGame().getWinner(),
                game.getStatus().toString(),
                0 // Host is always Player 1 (role 0)
        );
    }

    @MessageMapping("/game.join")
    @SendToUser(destinations = "/queue/game.details", broadcast = false)
    public InitialGameDetailsDTO joinGame(@Payload JoinGameRequest joinRequest, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        String gameId = joinRequest.getGameId(); // This line caused the error
        logger.info("Session [{}] attempting to join game [{}].", sessionId, gameId);

        OnlineMancalaGame game = gameManager.addPlayerToGame(gameId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found, full, or started."));

        GameStateDTO gameStateUpdate = new GameStateDTO(
                game.getGameId(),
                game.getMancalaGame().getBoard(),
                game.getMancalaGame().getCurrentPlayer(),
                game.getMancalaGame().isGameOver(),
                game.getMancalaGame().getWinner(),
                game.getStatus().toString()
        );
        messagingTemplate.convertAndSend("/topic/game/" + gameId, gameStateUpdate);
        logger.info("Session [{}] joined game [{}]. Broadcasted state.", sessionId, gameId);

        return new InitialGameDetailsDTO(
                game.getGameId(),
                game.getMancalaGame().getBoard(),
                game.getMancalaGame().getCurrentPlayer(),
                game.getMancalaGame().isGameOver(),
                game.getMancalaGame().getWinner(),
                game.getStatus().toString(),
                1 // Joiner is always Player 2 (role 1)
        );
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorDTO handleException(Throwable throwable) {
        logger.error("Error handling message: {}", throwable.getMessage());
        return new ErrorDTO(throwable.getMessage());
    }

    @MessageMapping("/game.{gameId}.move")
    public void makeMove(@DestinationVariable String gameId, @Payload MoveRequest moveRequest, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        int pitIndex = moveRequest.getPitIndex(); // This line caused the error

        gameManager.getGame(gameId).ifPresent(game -> {
            synchronized (game) {
                try {
                    int role = gameManager.getPlayerRoleInGame(gameId, sessionId);
                    if (role == -1) throw new IllegalArgumentException("You are not an active player in this game.");

                    if (game.makeMove(pitIndex, role)) {
                        GameStateDTO update = new GameStateDTO(
                                game.getGameId(),
                                game.getMancalaGame().getBoard(),
                                game.getMancalaGame().getCurrentPlayer(),
                                game.getMancalaGame().isGameOver(),
                                game.getMancalaGame().getWinner(),
                                game.getStatus().toString()
                        );
                        messagingTemplate.convertAndSend("/topic/game/" + gameId, update);
                    }
                } catch (IllegalArgumentException e) {
                    messagingTemplate.convertAndSendToUser(sessionId, "/queue/errors", new ErrorDTO(e.getMessage()));
                }
            }
        });
    }

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.wrap(event.getMessage()).getSessionId();
        logger.info("WebSocket connection established for session: {}", sessionId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        logger.info("WebSocket disconnected for session: {}", event.getSessionId());
        gameManager.removePlayer(event.getSessionId());
    }

    // --- INNER CLASSES WITH GETTERS ADDED BACK ---
    public static class JoinGameRequest {
        private String gameId;
        public String getGameId() { return gameId; } // This was missing
        public void setGameId(String gameId) { this.gameId = gameId; }
    }

    public static class MoveRequest {
        private int pitIndex;
        public int getPitIndex() { return pitIndex; } // This was missing
        public void setPitIndex(int pitIndex) { this.pitIndex = pitIndex; }
    }
}