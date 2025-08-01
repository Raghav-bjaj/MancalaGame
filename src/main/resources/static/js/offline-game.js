// src/main/resources/static/js/offline-game.js
document.addEventListener('DOMContentLoaded', () => {
    // DOM elements
    const statusMessageElement = document.getElementById('status-message');
    const gameBoardContainer = document.getElementById('game-board-container');
    const errorMessageElement = document.getElementById('error-message');
    const newGameForm = document.getElementById('newGameForm');

    let currentGameState = window.initialGame;
    let currentErrorMessage = window.initialErrorMessage;

    if (newGameForm) {
        newGameForm.addEventListener('submit', () => {
            console.log('New game form submitted.');
        });
    }

    function renderBoard(gameState) {
        if (!gameState || !gameState.board) {
            console.error("Invalid game state for rendering:", gameState);
            return;
        }

        gameBoardContainer.innerHTML = '';

        const player2PitsRow = document.createElement('div');
        player2PitsRow.className = 'player-pits top-row';
        for (let i = 12; i >= 7; i--) {
            const isClickable = gameState.currentPlayer === 1 && !gameState.gameOver && gameState.board[i] > 0;
            player2PitsRow.appendChild(createPitButton(i, gameState.board[i], isClickable));
        }

        const storesRow = document.createElement('div');
        storesRow.className = 'stores-row';
        storesRow.appendChild(createStoreElement(13, gameState.board[13], 'Player 2 Store'));
        const spacer = document.createElement('div');
        spacer.className = 'board-spacer';
        storesRow.appendChild(spacer);
        storesRow.appendChild(createStoreElement(6, gameState.board[6], 'Player 1 Store'));

        const player1PitsRow = document.createElement('div');
        player1PitsRow.className = 'player-pits bottom-row';
        for (let i = 0; i <= 5; i++) {
            const isClickable = gameState.currentPlayer === 0 && !gameState.gameOver && gameState.board[i] > 0;
            player1PitsRow.appendChild(createPitButton(i, gameState.board[i], isClickable));
        }

        if (!gameState.gameOver) {
            if (gameState.currentPlayer === 0) {
                player1PitsRow.classList.add('active-turn');
            } else {
                player2PitsRow.classList.add('active-turn');
            }
        }

        gameBoardContainer.appendChild(player2PitsRow);
        gameBoardContainer.appendChild(storesRow);
        gameBoardContainer.appendChild(player1PitsRow);

        updateStatusMessage(gameState);

        if (currentErrorMessage) {
            errorMessageElement.textContent = currentErrorMessage;
            errorMessageElement.style.display = 'block';
            currentErrorMessage = null;
        } else {
            errorMessageElement.style.display = 'none';
        }
    }

    function createPitButton(pitIndex, stones, isClickable) {
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit';

        const form = document.createElement('form');
        form.action = '/move';
        form.method = 'post';

        const button = document.createElement('button');
        button.type = 'submit';
        button.name = 'pitIndex';
        button.value = pitIndex;
        button.textContent = stones;
        button.classList.add('pit-button-element');

        if (!isClickable) {
            button.disabled = true;
        } else {
            button.classList.add('active-pit');
        }

        form.appendChild(button);
        pitDiv.appendChild(form);
        return pitDiv;
    }

    function createStoreElement(storeIndex, stones, label) {
        const storeDiv = document.createElement('div');
        storeDiv.className = 'store';
        storeDiv.innerHTML = `<span class="store-label">${label}</span><span class="store-stones">${stones}</span>`;
        return storeDiv;
    }

    function updateStatusMessage(gameState) {
        if (gameState.gameOver) {
            let winnerText = 'It\'s a draw!';
            if (gameState.winner === 0) winnerText = 'Player 1 wins!';
            if (gameState.winner === 1) winnerText = 'Player 2 wins!';
            statusMessageElement.textContent = `Game Over! ${winnerText}`;
        } else {
            statusMessageElement.textContent = `It's Player ${gameState.currentPlayer + 1}'s turn.`;
        }
    }

    if (currentGameState) {
        renderBoard(currentGameState);
    } else {
        statusMessageElement.textContent = 'Error: Game state not initialized.';
    }
});