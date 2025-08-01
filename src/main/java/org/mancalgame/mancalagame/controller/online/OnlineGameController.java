package org.mancalgame.mancalagame.controller.online;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.mancalgame.mancalagame.game.MancalaGame;
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

@Controller
public class OnlineGameController {

    private static final Logger logger = LoggerFactory.getLogger(OnlineGameController.class);

    private final OnlineGameManager gameManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final MancalaGameService mancalaGameService;

    public OnlineGameController(OnlineGameManager gameManager, SimpMessagingTemplate messagingTemplate, MancalaGameService mancalaGameService) {
        this.gameManager = gameManager;
        this.messagingTemplate = messagingTemplate;
        this.mancalaGameService = mancalaGameService;
    }

    // --- UPDATED: GameStateDTO now includes rematch flags and a new constructor ---
    public static class GameStateDTO {
        private String gameId;
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        private List<Integer> board;
        private int currentPlayer;
        private int winner;
        private boolean gameOver;
        private String gameStatus;
        private boolean player1WantsRematch; // New
        private boolean player2WantsRematch; // New

        public GameStateDTO() {}

        // New constructor to simplify creation
        public GameStateDTO(OnlineMancalaGame game) {
            MancalaGame coreGame = game.getMancalaGame();
            this.gameId = game.getGameId();
            this.board = Arrays.stream(coreGame.getBoard()).boxed().collect(Collectors.toList());
            this.currentPlayer = coreGame.getCurrentPlayer();
            this.gameOver = coreGame.isGameOver();
            this.winner = coreGame.getWinner();
            this.gameStatus = game.getStatus().toString();
            this.player1WantsRematch = game.isPlayer1WantsRematch();
            this.player2WantsRematch = game.isPlayer2WantsRematch();
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
        public boolean isPlayer1WantsRematch() { return player1WantsRematch; }
        public void setPlayer1WantsRematch(boolean player1WantsRematch) { this.player1WantsRematch = player1WantsRematch; }
        public boolean isPlayer2WantsRematch() { return player2WantsRematch; }
        public void setPlayer2WantsRematch(boolean player2WantsRematch) { this.player2WantsRematch = player2WantsRematch; }
    }

    public static class InitialGameDetailsDTO extends GameStateDTO {
        private int assignedPlayerRole;

        public InitialGameDetailsDTO(OnlineMancalaGame game, int assignedPlayerRole) {
            super(game);
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
        OnlineMancalaGame game = gameManager.createGame();
        gameManager.addPlayerToGame(game.getGameId(), sessionId);
        return new InitialGameDetailsDTO(game, 0);
    }

    @MessageMapping("/game.join")
    @SendToUser(destinations = "/queue/game.details", broadcast = false)
    public InitialGameDetailsDTO joinGame(@Payload JoinGameRequest joinRequest, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        String gameId = joinRequest.getGameId();
        OnlineMancalaGame game = gameManager.addPlayerToGame(gameId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found, is full, or has already started."));

        messagingTemplate.convertAndSend("/topic/game/" + gameId, new GameStateDTO(game));
        return new InitialGameDetailsDTO(game, 1);
    }

    // --- NEW: Message mapping for rematch requests ---
    @MessageMapping("/game.{gameId}.rematch")
    public void requestRematch(@DestinationVariable String gameId, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        gameManager.getGame(gameId).ifPresent(game -> {
            synchronized(game) {
                int role = gameManager.getPlayerRoleInGame(gameId, sessionId);
                game.setPlayerWantsRematch(role);

                if (game.bothPlayersWantRematch()) {
                    game.resetForRematch();
                }

                // Broadcast the updated state to both players
                messagingTemplate.convertAndSend("/topic/game/" + gameId, new GameStateDTO(game));
            }
        });
    }

    @MessageMapping("/game.{gameId}.move")
    public void makeMove(@DestinationVariable String gameId, @Payload MoveRequest moveRequest, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        gameManager.getGame(gameId).ifPresent(game -> {
            synchronized (game) {
                try {
                    int role = gameManager.getPlayerRoleInGame(gameId, sessionId);
                    if (game.makeMove(moveRequest.getPitIndex(), role)) {
                        messagingTemplate.convertAndSend("/topic/game/" + gameId, new GameStateDTO(game));
                    }
                } catch (IllegalArgumentException e) {
                    messagingTemplate.convertAndSendToUser(sessionId, "/queue/errors", new ErrorDTO(e.getMessage()));
                }
            }
        });
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorDTO handleException(Throwable throwable) {
        logger.error("Error handling message: {}", throwable.getMessage());
        return new ErrorDTO(throwable.getMessage());
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