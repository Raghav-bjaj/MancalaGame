// src/main/resources/static/js/online-game.js

console.log("online-game.js script started loading.");

// --- WebSocket Connection Setup ---
const socket = new SockJS('/ws', null, {
    transports: ['websocket', 'xhr-streaming', 'xhr-polling']
});
const stompClient = Stomp.over(socket);

// --- Game State & DOM References ---
let isConnected = false, gameId = null, playerRole = null;
const gameOptionsDiv = document.getElementById('gameOptions');
const createGameButton = document.getElementById('createGameButton');
const joinGameButton = document.getElementById('joinGameButton');
const joinGameIdInput = document.getElementById('joinGameId');
const gameAreaDiv = document.getElementById('gameArea');
const statusMessageElement = document.getElementById('statusMessage');
const errorMessageElement = document.getElementById('errorMessage');
const displayGameIdElement = document.getElementById('displayGameId');
const displayPlayerRoleElement = document.getElementById('displayPlayerRole');
const boardDiv = document.getElementById('online-game-board-container');
const interactiveButtons = [createGameButton, joinGameButton, joinGameIdInput];
const inGameControls = document.getElementById('inGameControls');
const endGameControls = document.getElementById('endGameControls');
const playAgainButton = document.getElementById('playAgainButton');

// --- Event Listeners ---
if (createGameButton) createGameButton.addEventListener('click', sendCreateGameMessage);
if (joinGameButton) joinGameButton.addEventListener('click', sendJoinGameMessage);
if (playAgainButton) playAgainButton.addEventListener('click', sendRematchRequest);

// --- WebSocket Functions ---
function connect() {
    stompClient.connect({}, (frame) => {
        isConnected = true;
        console.log('Connected: ' + frame);
        stompClient.subscribe('/user/queue/game.details', onGameDetailsReceived);
        stompClient.subscribe('/user/queue/errors', onErrorReceived);
    }, (error) => { console.error('STOMP connection error: ' + error); });
}

function ensureTopicSubscription() {
    if (gameId && isConnected) {
        const subId = 'gameTopicSub_' + gameId;
        if (!stompClient.subscriptions[subId]) {
            stompClient.subscribe('/topic/game/' + gameId, onGameStateUpdate, { id: subId });
        }
    }
}

// --- Message Sending Functions ---
function sendCreateGameMessage() {
    if (!isConnected) return;
    disableGameButtons();
    stompClient.send("/app/game.host", {}, "{}");
}

function sendJoinGameMessage() {
    if (!isConnected) return;
    const enteredGameId = joinGameIdInput.value.trim();
    if (!enteredGameId) return;
    disableGameButtons();
    stompClient.send("/app/game.join", {}, JSON.stringify({ 'gameId': enteredGameId }));
}

function makeMove(pitIndex) {
    if (!stompClient.connected || !gameId) return;
    disablePitClicks();
    stompClient.send("/app/game." + gameId + ".move", {}, JSON.stringify({ 'pitIndex': pitIndex }));
}

function sendRematchRequest() {
    if (!stompClient.connected || !gameId) return;
    playAgainButton.disabled = true;
    playAgainButton.textContent = "Waiting...";
    stompClient.send("/app/game." + gameId + ".rematch", {}, "{}");
}

// --- Message Receiving Handlers ---
function onGameDetailsReceived(payload) {
    let receivedState = JSON.parse(payload.body);
    playerRole = receivedState.assignedPlayerRole;
    gameId = receivedState.gameId;
    displayPlayerRoleElement.textContent = `You are Player ${playerRole + 1}`;
    displayGameIdElement.textContent = 'Game ID: ' + gameId;
    ensureTopicSubscription();
    gameOptionsDiv.style.display = 'none';
    gameAreaDiv.style.display = 'block';
    if(inGameControls) inGameControls.style.display = 'none';
    updateGameBoardUI(receivedState);
    updateGameStatusMessage(receivedState);
}

function onGameStateUpdate(payload) {
    let gameState = JSON.parse(payload.body);

    if (!gameState.gameOver) {
        if (endGameControls) endGameControls.style.display = 'none';
        if (playAgainButton) {
            playAgainButton.disabled = false;
            playAgainButton.textContent = "Play Again";
        }
    }

    updateGameBoardUI(gameState);
    updateGameStatusMessage(gameState);

    if (gameState.gameStatus === 'FINISHED' || gameState.gameStatus === 'CANCELLED') {
        disablePitClicks();
        if(endGameControls) endGameControls.style.display = 'flex';
    }
}

function onErrorReceived(payload) {
    let error = JSON.parse(payload.body);
    errorMessageElement.textContent = error.message;
    errorMessageElement.style.display = 'block';
    enableGameButtons();
}

// --- UI Rendering ---
function updateGameBoardUI(gameState) {
    if (!boardDiv) return;
    boardDiv.innerHTML = '';
    const isPlayer1 = (playerRole === 0);

    const playerPits = isPlayer1 ? { start: 0, end: 5 } : { start: 7, end: 12 };
    const opponentPits = isPlayer1 ? { start: 7, end: 12 } : { start: 0, end: 5 };
    const playerStoreIndex = isPlayer1 ? 6 : 13;
    const opponentStoreIndex = isPlayer1 ? 13 : 6;
    const playerStoreLabel = `Your Store (P${playerRole + 1})`;
    const opponentStoreLabel = `Opponent's Store (P${isPlayer1 ? 2 : 1})`;

    const opponentRow = document.createElement('div');
    opponentRow.className = 'player-pits top-row';
    for (let i = opponentPits.end; i >= opponentPits.start; i--) {
        opponentRow.appendChild(createPitButton(i, gameState.board[i], false));
    }

    const storesRow = document.createElement('div');
    storesRow.className = 'stores-row';
    storesRow.appendChild(createStoreElement(opponentStoreIndex, gameState.board[opponentStoreIndex], opponentStoreLabel));
    const spacer = document.createElement('div');
    spacer.className = 'board-spacer';
    storesRow.appendChild(spacer);
    storesRow.appendChild(createStoreElement(playerStoreIndex, gameState.board[playerStoreIndex], playerStoreLabel));

    const playerRow = document.createElement('div');
    playerRow.className = 'player-pits bottom-row';
    const isMyTurn = (gameState.currentPlayer === playerRole);
    for (let i = playerPits.start; i <= playerPits.end; i++) {
        const isClickable = isMyTurn && !gameState.gameOver && gameState.board[i] > 0;
        playerRow.appendChild(createPitButton(i, gameState.board[i], isClickable));
    }

    if (isMyTurn && !gameState.gameOver) {
        playerRow.classList.add('active-turn');
    }

    boardDiv.appendChild(opponentRow);
    boardDiv.appendChild(storesRow);
    boardDiv.appendChild(playerRow);
}

function createPitButton(index, stones, isClickable) {
    const pitDiv = document.createElement('div');
    pitDiv.className = 'pit';
    const button = document.createElement('button');
    button.textContent = stones;
    button.classList.add('pit-button-element');
    button.disabled = !isClickable;
    if (isClickable) {
        button.classList.add('active-pit');
        button.onclick = () => makeMove(index);
    }
    pitDiv.appendChild(button);
    return pitDiv;
}

function createStoreElement(index, stones, label) {
    const storeDiv = document.createElement('div');
    storeDiv.className = 'store';
    storeDiv.innerHTML = `<span class="store-label">${label}</span><span class="store-stones">${stones}</span>`;
    return storeDiv;
}

function updateGameStatusMessage(gameState) {
    errorMessageElement.style.display = 'none';
    if (gameState.gameOver) {
        const player1Ready = gameState.player1WantsRematch;
        const player2Ready = gameState.player2WantsRematch;
        const myRematchStatus = (playerRole === 0) ? player1Ready : player2Ready;
        const opponentRematchStatus = (playerRole === 0) ? player2Ready : player1Ready;

        let winnerText = 'It\'s a draw!';
        if (gameState.winner === playerRole) winnerText = 'You won!';
        else if (gameState.winner !== -1) winnerText = 'You lost.';

        statusMessageElement.textContent = `Game Over! ${winnerText}`;

        if(playAgainButton) {
            if (myRematchStatus) {
                playAgainButton.disabled = true;
                playAgainButton.textContent = "Waiting...";
            }
            if (opponentRematchStatus && !myRematchStatus) {
                statusMessageElement.textContent += " Your opponent wants a rematch!";
            }
        }
    } else if (gameState.gameStatus === 'WAITING_FOR_PLAYER') {
        statusMessageElement.textContent = 'Waiting for opponent...';
    } else if (gameState.gameStatus === 'IN_PROGRESS') {
        statusMessageElement.textContent = gameState.currentPlayer === playerRole ? 'Your turn.' : "Opponent's turn.";
    }
}

function disablePitClicks() {
    boardDiv.querySelectorAll('.pit-button-element').forEach(button => {
        button.disabled = true;
        button.classList.remove('active-pit');
        button.onclick = null;
    });
}

function disableGameButtons() { interactiveButtons.forEach(b => b.disabled = true); }
function enableGameButtons() { interactiveButtons.forEach(b => b.disabled = false); }

// --- Start Connection ---
connect();