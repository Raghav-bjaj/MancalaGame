console.log("online-game.js script started loading.");

const socket = new SockJS('/ws', null, {
    transports: ['websocket', 'xhr-streaming', 'xhr-polling']
});
const stompClient = Stomp.over(socket);

stompClient.debug = function(str) {
    console.log("STOMP Debug:", str);
};

const originalStompWsOnMessage = stompClient.ws.onmessage;
stompClient.ws.onmessage = function(event) {
    let data = event.data;
    console.log("Intercepted raw WebSocket message (from SockJS):", data);
    if (typeof data === 'string' && data.startsWith('a[') && data.endsWith(']')) {
        try {
            const messages = JSON.parse(data.substring(1));
            if (Array.isArray(messages)) {
                messages.forEach(msg => {
                    if (typeof msg === 'string') {
                        console.log("Manually unwrapped STOMP frame:", msg);
                        originalStompWsOnMessage.call(stompClient.ws, { data: msg });
                    }
                });
                return;
            }
        } catch (e) {
            console.error("Error parsing SockJS message array:", e, "Original data:", data);
        }
    }
    originalStompWsOnMessage.call(stompClient.ws, event);
};

stompClient.onreceive = function(frame) {
    console.log("STOMP Client onreceive triggered for frame command:", frame.command, "Headers:", frame.headers);
    if (frame.command === 'MESSAGE') {
        console.log("onreceive: MESSAGE frame body:", frame.body);
    }
};

socket.onclose = function() {
    console.log("SockJS connection closed.");
};
socket.onerror = function(e) {
    console.error("SockJS error:", e);
};

let isConnected = false;
let gameId = null;
let playerRole = null;
let currentGameState = null;

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

let criticalElementsMissing = false;
if (!gameOptionsDiv) { console.error("CRITICAL ERROR: 'gameOptions' div not found."); criticalElementsMissing = true; }
if (!createGameButton) { console.error("CRITICAL ERROR: 'createGameButton' not found."); criticalElementsMissing = true; }
if (!joinGameButton) { console.error("CRITICAL ERROR: 'joinGameButton' not found."); criticalElementsMissing = true; }
if (!gameAreaDiv) { console.error("CRITICAL ERROR: 'gameArea' div not found."); criticalElementsMissing = true; }
if (!statusMessageElement) { console.error("CRITICAL ERROR: 'statusMessage' element not found."); criticalElementsMissing = true; }
if (!errorMessageElement) { console.error("CRITICAL ERROR: 'errorMessage' element not found."); criticalElementsMissing = true; }
if (!displayGameIdElement) { console.error("CRITICAL ERROR: 'displayGameId' element not found."); criticalElementsMissing = true; }
if (!displayPlayerRoleElement) { console.error("CRITICAL ERROR: 'displayPlayerRole' element not found."); criticalElementsMissing = true; }
if (!boardDiv) { console.error("CRITICAL ERROR: 'online-game-board-container' div not found."); criticalElementsMissing = true; }

if (criticalElementsMissing) {
    console.error("Stopping script execution due to missing critical UI elements.");
    if (errorMessageElement) {
        errorMessageElement.textContent = "Error: Essential game UI elements are missing. Please check the HTML file.";
    }
} else {
    console.log("All critical DOM elements referenced successfully.");
}

const interactiveButtons = [createGameButton, joinGameButton, joinGameIdInput];

if (createGameButton) {
    createGameButton.addEventListener('click', sendCreateGameMessage);
    console.log("Event listener attached to 'Host New Game' button.");
}
if (joinGameButton) {
    joinGameButton.addEventListener('click', sendJoinGameMessage);
    console.log("Event listener attached to 'Join Game' button.");
}

function connect() {
    console.log('Attempting to connect to WebSocket...');
    stompClient.connect({},
        function (frame) {
            isConnected = true;
            console.log('Connected: ' + frame);
            if (errorMessageElement) {
                errorMessageElement.textContent = '';
                errorMessageElement.style.display = 'none';
            }

            stompClient.heartbeat.outgoing = 10000;
            stompClient.heartbeat.incoming = 10000;

            // --- THESE ARE THE CORRECTED LINES ---
            stompClient.subscribe('/user/queue/game.details', (payload) => onGameDetailsReceived(payload), { id: 'gameDetailsSub' });
            stompClient.subscribe('/user/queue/errors', (payload) => onErrorReceived(payload), { id: 'errorQueueSub' });

            console.log("Subscribed to user-specific queues.");

        },
        function(error) {
            isConnected = false;
            console.error('STOMP connection error or disconnect: ' + error);
            if (errorMessageElement) {
                errorMessageElement.textContent = 'Connection lost. Please refresh or try again.';
                errorMessageElement.style.display = 'block';
            }
            if (statusMessageElement) statusMessageElement.textContent = 'Disconnected.';
            if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
            if (gameAreaDiv) gameAreaDiv.style.display = 'none';
            enableGameButtons();

            if (gameId) {
                stompClient.unsubscribe('gameTopicSub_' + gameId);
                gameId = null;
            }
            console.log("WebSocket connection failed/disconnected. UI reverted to options.");
        }
    );
}

function ensureTopicSubscription() {
    if (gameId && isConnected && stompClient && stompClient.connected) {
        const subscriptionId = 'gameTopicSub_' + gameId;
        if (!stompClient.subscriptions[subscriptionId]) {
            stompClient.subscribe('/topic/game/' + gameId, (payload) => onGameStateUpdate(payload), { id: subscriptionId });
            console.log("Subscribed to game topic: /topic/game/" + gameId);
        } else {
            console.log("Already subscribed to game topic: /topic/game/" + gameId);
        }
    } else {
        console.warn("Cannot subscribe to game topic: Not connected or gameId is null.");
    }
}

function sendCreateGameMessage() {
    if (!isConnected || !stompClient || !stompClient.connected) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Not connected to server. Please wait or refresh.';
            errorMessageElement.style.display = 'block';
        }
        return;
    }
    disableGameButtons();
    if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = 'You are Player 1 (Host)';
    if (displayGameIdElement) displayGameIdElement.textContent = 'Game ID: (Generating...)';
    if (statusMessageElement) statusMessageElement.textContent = 'Sending host request...';
    if (errorMessageElement) {
        errorMessageElement.textContent = '';
        errorMessageElement.style.display = 'none';
    }
    stompClient.send("/app/game.host", { 'content-type': 'application/json' }, {});
}

function sendJoinGameMessage() {
    if (!isConnected || !stompClient || !stompClient.connected) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Not connected to server. Please wait or refresh.';
            errorMessageElement.style.display = 'block';
        }
        return;
    }
    const enteredGameId = joinGameIdInput ? joinGameIdInput.value.trim() : '';
    if (!enteredGameId) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Please enter a Game ID to join.';
            errorMessageElement.style.display = 'block';
        }
        return;
    }
    disableGameButtons();
    if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = 'You are Player 2 (Joiner)';
    if (displayGameIdElement) displayGameIdElement.textContent = 'Game ID: ' + enteredGameId;
    if (statusMessageElement) statusMessageElement.textContent = `Joining game ${enteredGameId}...`;
    if (errorMessageElement) {
        errorMessageElement.textContent = '';
        errorMessageElement.style.display = 'none';
    }
    stompClient.send("/app/game.join", { 'content-type': 'application/json' }, JSON.stringify({ 'gameId': enteredGameId }));
}

function disableGameButtons() {
    interactiveButtons.forEach(button => { if (button) button.disabled = true; });
    console.log("Game options buttons disabled.");
}

function enableGameButtons() {
    interactiveButtons.forEach(button => { if (button) button.disabled = false; });
    console.log("Game options buttons enabled.");
}

function onGameDetailsReceived(payload) {
    console.log('Raw game details payload received:', payload.body);
    let receivedState;
    try {
        receivedState = JSON.parse(payload.body);
        console.log('Parsed Initial Game State (Details):', receivedState);

        if (typeof receivedState.assignedPlayerRole !== 'undefined' && receivedState.assignedPlayerRole !== null) {
            playerRole = receivedState.assignedPlayerRole;
            if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = `You are Player ${playerRole + 1}`;
        }

        if (receivedState.gameId) {
            gameId = receivedState.gameId;
            if (displayGameIdElement) displayGameIdElement.textContent = 'Game ID: ' + gameId;
            ensureTopicSubscription();
        } else {
            errorMessageElement.textContent = "Error: Invalid game details received from server (missing Game ID).";
            errorMessageElement.style.display = 'block';
            enableGameButtons();
            return;
        }

        if (gameOptionsDiv) gameOptionsDiv.style.display = 'none';
        if (gameAreaDiv) gameAreaDiv.style.display = 'block';

        if (receivedState.board && Array.isArray(receivedState.board) && receivedState.board.length === 14) {
            updateGameBoardUI(receivedState);
            updateGameStatusMessage(receivedState);
        } else {
            errorMessageElement.textContent = 'Error: Invalid initial game board data received.';
            errorMessageElement.style.display = 'block';
            enableGameButtons();
        }
    } catch (e) {
        console.error('Failed to parse initial game details JSON:', e, 'Payload body:', payload.body);
        errorMessageElement.textContent = 'Error: Failed to process initial game details.';
        errorMessageElement.style.display = 'block';
        enableGameButtons();
    }
}

function onGameStateUpdate(payload) {
    console.log('Raw game state update payload received:', payload.body);
    let gameState;
    try {
        gameState = JSON.parse(payload.body);
        console.log("Client: Game state update received:", gameState);
        if (gameState && Array.isArray(gameState.board) && gameState.board.length === 14) {
            currentGameState = gameState;
            updateGameBoardUI(gameState);
            updateGameStatusMessage(gameState);
        } else {
            errorMessageElement.textContent = "Error: Invalid game state received from server.";
            errorMessageElement.style.display = 'block';
        }

        if (gameState.gameStatus === 'FINISHED' || gameState.gameStatus === 'CANCELLED') {
            disablePitClicks();
            setTimeout(() => {
                enableGameButtons();
                if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
                if (gameAreaDiv) gameAreaDiv.style.display = 'none';
            }, 5000);
        }
    } catch (e) {
        console.error("Failed to parse game state update JSON:", e, 'Payload body:', payload.body);
    }
}

function onErrorReceived(payload) {
    let errorMsg = 'An unknown error occurred.';
    try {
        errorMsg = JSON.parse(payload.body).message || payload.body;
    } catch(e) {
        errorMsg = payload.body;
    }
    console.error('Error received from server:', errorMsg);
    if (errorMessageElement) {
        errorMessageElement.textContent = errorMsg;
        errorMessageElement.style.display = 'block';
    }
    if (statusMessageElement) statusMessageElement.textContent = 'Error occurred.';
    if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
    if (gameAreaDiv) gameAreaDiv.style.display = 'none';
    enableGameButtons();
}

function updateGameBoardUI(gameState) {
    if (!boardDiv) return;
    boardDiv.innerHTML = '';

    const player2PitsRow = document.createElement('div');
    player2PitsRow.className = 'player-pits top-row';
    for (let i = 12; i >= 7; i--) {
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit';
        const button = document.createElement('button');
        button.textContent = gameState.board[i];
        button.dataset.index = i;
        button.classList.add('pit-button-element');
        pitDiv.appendChild(button);
        player2PitsRow.appendChild(pitDiv);
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
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit';
        const button = document.createElement('button');
        button.textContent = gameState.board[i];
        button.dataset.index = i;
        button.classList.add('pit-button-element');
        pitDiv.appendChild(button);
        player1PitsRow.appendChild(pitDiv);
    }

    boardDiv.appendChild(player2PitsRow);
    boardDiv.appendChild(storesRow);
    boardDiv.appendChild(player1PitsRow);
    enablePitClicks(gameState);
}

function createStoreElement(storeIndex, stones, label) {
    const storeDiv = document.createElement('div');
    storeDiv.className = 'store';
    storeDiv.innerHTML = `<span class="store-label">${label}</span><span class="store-stones">${stones}</span>`;
    return storeDiv;
}

function updateGameStatusMessage(gameState) {
    if (!statusMessageElement) return;
    if (errorMessageElement) {
        errorMessageElement.textContent = '';
        errorMessageElement.style.display = 'none';
    }

    if (gameState.gameOver) {
        if (gameState.winner === playerRole) statusMessageElement.textContent = 'Game Over! You won!';
        else if (gameState.winner === 1 - playerRole) statusMessageElement.textContent = 'Game Over! You lost.';
        else statusMessageElement.textContent = 'Game Over! It\'s a draw!';
        statusMessageElement.style.color = '#dc3545';
    } else {
        statusMessageElement.style.color = '#007bff';
        if (gameState.gameStatus === 'WAITING_FOR_PLAYER') {
            statusMessageElement.textContent = 'Waiting for opponent to join... Share Game ID: ' + gameId;
        } else if (gameState.gameStatus === 'IN_PROGRESS') {
            statusMessageElement.textContent = gameState.currentPlayer === playerRole ? 'Your turn.' : 'Opponent\'s turn.';
        } else if (gameState.gameStatus === 'CANCELLED') {
            statusMessageElement.textContent = 'Game Cancelled. Opponent disconnected.';
        }
    }
}

function enablePitClicks(gameState) {
    disablePitClicks();
    if (gameState.gameOver || gameState.gameStatus !== 'IN_PROGRESS') return;

    const pits = boardDiv ? boardDiv.querySelectorAll('.pit-button-element') : [];
    pits.forEach(button => {
        const index = parseInt(button.dataset.index);
        if (playerRole === gameState.currentPlayer && isOwnPit(index, playerRole) && gameState.board[index] > 0) {
            button.disabled = false;
            button.classList.add('active-pit');
            button.onclick = () => makeMove(index);
        }
    });
}

function disablePitClicks() {
    const pits = boardDiv ? boardDiv.querySelectorAll('.pit-button-element') : [];
    pits.forEach(button => {
        button.disabled = true;
        button.classList.remove('active-pit');
        button.onclick = null;
    });
}

function isOwnPit(index, player) {
    return (player === 0 && index >= 0 && index <= 5) || (player === 1 && index >= 7 && index <= 12);
}

function makeMove(pitIndex) {
    if (!stompClient || !stompClient.connected || gameId === null) {
        if (errorMessageElement) {
            errorMessageElement.textContent = "Error: Not connected to game server.";
            errorMessageElement.style.display = 'block';
        }
        return;
    }
    disablePitClicks();
    if (statusMessageElement) statusMessageElement.textContent = 'Processing your move...';
    stompClient.send("/app/game." + gameId + ".move", {}, JSON.stringify({ 'pitIndex': pitIndex }));
}

connect();