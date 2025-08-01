package org.mancalgame.mancalagame.Service;

import org.mancalgame.mancalagame.game.MancalaGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class MancalaGameService {

    private static final Logger logger = LoggerFactory.getLogger(MancalaGameService.class);

    public MancalaGame createNewGame() {
        return new MancalaGame();
    }

    public boolean makeMove(MancalaGame game, int pitIndex) {
        if (game.isGameOver()) return false;

        int currentPlayer = game.getCurrentPlayer();
        if ((currentPlayer == 0 && (pitIndex < 0 || pitIndex > 5)) ||
                (currentPlayer == 1 && (pitIndex < 7 || pitIndex > 12))) {
            throw new IllegalArgumentException("Invalid pit selection for Player " + (currentPlayer + 1));
        }
        if (game.getStonesInPit(pitIndex) == 0) {
            throw new IllegalArgumentException("Cannot select an empty pit.");
        }

        int[] board = game.getBoard();
        int stonesToSow = board[pitIndex];
        board[pitIndex] = 0;
        int currentPit = pitIndex;
        int opponentStore = (currentPlayer == 0) ? MancalaGame.PLAYER2_STORE : MancalaGame.PLAYER1_STORE;

        while (stonesToSow > 0) {
            currentPit = (currentPit + 1) % 14;
            if (currentPit == opponentStore) continue;
            board[currentPit]++;
            stonesToSow--;
        }

        int playerStore = (currentPlayer == 0) ? MancalaGame.PLAYER1_STORE : MancalaGame.PLAYER2_STORE;
        boolean lastPitOnPlayersSide = (currentPlayer == 0 && currentPit >= 0 && currentPit <= 5) ||
                (currentPlayer == 1 && currentPit >= 7 && currentPit <= 12);

        if (lastPitOnPlayersSide && board[currentPit] == 1) {
            int oppositePit = 12 - currentPit;
            if (board[oppositePit] > 0) {
                board[playerStore] += board[oppositePit] + 1;
                board[oppositePit] = 0;
                board[currentPit] = 0;
            }
        }

        game.setBoard(board);

        if (isPlayerPitsEmpty(game, 0) || isPlayerPitsEmpty(game, 1)) {
            collectRemainingStones(game);
            determineWinner(game);
            game.setGameOver(true);
            return true;
        }

        if (currentPit != playerStore) {
            game.setCurrentPlayer(1 - currentPlayer);
        }

        logger.info("Move successful for Player {}. Next turn: Player {}. Board: {}", (currentPlayer + 1), (game.getCurrentPlayer() + 1), Arrays.toString(game.getBoard()));
        return true;
    }

    private boolean isPlayerPitsEmpty(MancalaGame game, int player) {
        int start = (player == 0) ? 0 : 7;
        for (int i = start; i < start + 6; i++) {
            if (game.getStonesInPit(i) > 0) return false;
        }
        return true;
    }

    private void collectRemainingStones(MancalaGame game) {
        int[] finalBoard = game.getBoard();
        for (int i = 0; i <= 5; i++) {
            finalBoard[6] += finalBoard[i];
            finalBoard[i] = 0;
        }
        for (int i = 7; i <= 12; i++) {
            finalBoard[13] += finalBoard[i];
            finalBoard[i] = 0;
        }
        game.setBoard(finalBoard);
    }

    private void determineWinner(MancalaGame game) {
        if (game.getStonesInPit(6) > game.getStonesInPit(13)) game.setWinner(0);
        else if (game.getStonesInPit(13) > game.getStonesInPit(6)) game.setWinner(1);
        else game.setWinner(-1);
    }
}