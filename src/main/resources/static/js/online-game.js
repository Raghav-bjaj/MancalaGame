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

// --- Event Listeners ---
if (createGameButton) createGameButton.addEventListener('click', sendCreateGameMessage);
if (joinGameButton) joinGameButton.addEventListener('click', sendJoinGameMessage);

// --- WebSocket Functions ---
function connect() {
    stompClient.connect({}, (frame) => {
        isConnected = true;
        stompClient.subscribe('/user/queue/game.details', onGameDetailsReceived);
        stompClient.subscribe('/user/queue/errors', onErrorReceived);
    });
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
    updateGameBoardUI(receivedState);
    updateGameStatusMessage(receivedState);
}

function onGameStateUpdate(payload) {
    let gameState = JSON.parse(payload.body);
    updateGameBoardUI(gameState);
    updateGameStatusMessage(gameState);
    if (gameState.gameStatus === 'FINISHED' || gameState.gameStatus === 'CANCELLED') {
        disablePitClicks();
        setTimeout(() => window.location.reload(), 5000);
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

    // Create Opponent's Pits (Top Row)
    const opponentRow = document.createElement('div');
    opponentRow.className = 'player-pits top-row';
    for (let i = opponentPits.end; i >= opponentPits.start; i--) { // Counts down for correct L-R visual
        opponentRow.appendChild(createPitButton(i, gameState.board[i], false));
    }

    // Create Stores
    const storesRow = document.createElement('div');
    storesRow.className = 'stores-row';
    storesRow.appendChild(createStoreElement(opponentStoreIndex, gameState.board[opponentStoreIndex], opponentStoreLabel));
    const spacer = document.createElement('div');
    spacer.className = 'board-spacer';
    storesRow.appendChild(spacer);
    storesRow.appendChild(createStoreElement(playerStoreIndex, gameState.board[playerStoreIndex], playerStoreLabel));

    // Create Player's Pits (Bottom Row)
    const playerRow = document.createElement('div');
    playerRow.className = 'player-pits bottom-row';
    const isMyTurn = (gameState.currentPlayer === playerRole);
    for (let i = playerPits.start; i <= playerPits.end; i++) { // Counts up for correct L-R visual
        const isClickable = isMyTurn && !gameState.gameOver && gameState.board[i] > 0;
        playerRow.appendChild(createPitButton(i, gameState.board[i], isClickable));
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

function updateGameStatusMessage(gameState) { /* ... Unchanged ... */ }
function disablePitClicks() { /* ... Unchanged ... */ }
function disableGameButtons() { /* ... Unchanged ... */ }
function enableGameButtons() { /* ... Unchanged ... */ }

// --- Start Connection ---
connect();