// src/main/resources/static/js/online-game.js

// IMPORTANT: Ensure SockJS and Stomp.js libraries are loaded in your HTML (online-game.html)
// e.g.:
// <script src="[https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.5.0/sockjs.min.js](https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.5.0/sockjs.min.js)"></script>
// <script src="[https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js](https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js)"></script>

console.log("online-game.js script started loading."); // Confirm script loads

// WebSocket connection setup (endpoint matches WebSocketConfig.java '/ws')
// Explicitly define transports to prioritize websockets
const socket = new SockJS('/ws', null, {
    transports: ['websocket', 'xhr-streaming', 'xhr-polling']
});

const stompClient = Stomp.over(socket);

// Enable STOMP client debugging for verbose logging of frames
stompClient.debug = function(str) {
    // Log all STOMP debug messages, including raw incoming frames if they get processed
    console.log("STOMP Debug:", str);
};

// --- CRITICAL FIX: Manual unwrapping of SockJS messages for Stomp.js ---
// Stomp.js needs to receive raw STOMP frames. SockJS sometimes wraps them.
// We'll override the onmessage handler that Stomp.js uses for the underlying WebSocket/SockJS.
// This ensures that when SockJS delivers a message, we manually unwrap it if it's in the 'a["..."]' format.
const originalStompWsOnMessage = stompClient.ws.onmessage;

stompClient.ws.onmessage = function(event) {
    let data = event.data;
    console.log("Intercepted raw WebSocket message (from SockJS):", data);

    // Check if the message is in the SockJS array format (e.g., 'a["MESSAGE...", "..."]')
    if (typeof data === 'string' && data.startsWith('a[') && data.endsWith(']')) {
        try {
            // Parse the SockJS array. It will contain stringified STOMP frames.
            const messages = JSON.parse(data.substring(1)); // Remove 'a' prefix and parse
            if (Array.isArray(messages)) {
                messages.forEach(msg => {
                    if (typeof msg === 'string') {
                        // Pass the unwrapped STOMP frame string to Stomp.js's internal message handler
                        console.log("Manually unwrapped STOMP frame:", msg);
                        originalStompWsOnMessage.call(stompClient.ws, { data: msg });
                    }
                });
                return; // Prevent original handler from processing the raw 'a[...]'
            }
        } catch (e) {
            console.error("Error parsing SockJS message array:", e, "Original data:", data);
        }
    }
    // If not in the 'a[...]' format, or if parsing failed, let the original handler process it
    originalStompWsOnMessage.call(stompClient.ws, event);
};
// --- END CRITICAL FIX ---

// --- NEW: Direct onreceive listener for low-level STOMP frame debugging ---
stompClient.onreceive = function(frame) {
    console.log("STOMP Client onreceive triggered for frame command:", frame.command, "Headers:", frame.headers);
    if (frame.command === 'MESSAGE') {
        console.log("onreceive: MESSAGE frame body:", frame.body);
    }
    // Let the original onreceive handler (if any, or the default behavior) continue
    // Note: Stomp.js doesn't have a direct "next" handler for onreceive in the same way as onmessage.
    // This is primarily a listener for debugging.
};
// --- END NEW ---


socket.onclose = function() {
    console.log("SockJS connection closed.");
};
socket.onerror = function(e) {
    console.error("SockJS error:", e);
};


// Game state variables for the client
let isConnected = false;    // Tracks WebSocket connection status
let gameId = null;          // Unique ID of the current online game
let playerRole = null;      // Assigned role for this client (0 for Player 1, 1 for Player 2), set by server
let currentGameState = null; // The most recent game state received from the server

// DOM elements references - These are initialized immediately on script load
const gameOptionsDiv = document.getElementById('gameOptions');
const createGameButton = document.getElementById('createGameButton');
const joinGameButton = document.getElementById('joinGameButton');
const joinGameIdInput = document.getElementById('joinGameId');
const gameAreaDiv = document.getElementById('gameArea');
const statusMessageElement = document.getElementById('statusMessage');
const errorMessageElement = document.getElementById('errorMessage');
const displayGameIdElement = document.getElementById('displayGameId');
const displayPlayerRoleElement = document.getElementById('displayPlayerRole');
const boardDiv = document.getElementById('online-game-board-container'); // Corrected ID to match HTML

// --- CRITICAL CHECK: Verify all necessary DOM elements exist ---
// Log if any critical elements are missing right at the start
let criticalElementsMissing = false;
if (!gameOptionsDiv) { console.error("CRITICAL ERROR: 'gameOptions' div not found. Check online-game.html"); criticalElementsMissing = true; }
if (!createGameButton) { console.error("CRITICAL ERROR: 'createGameButton' not found. Check online-game.html"); criticalElementsMissing = true; }
if (!joinGameButton) { console.error("CRITICAL ERROR: 'joinGameButton' not found. Check online-game.html"); criticalElementsMissing = true; }
if (!gameAreaDiv) { console.error("CRITICAL ERROR: 'gameArea' div not found. Check online-game.html"); criticalElementsMissing = true; }
if (!statusMessageElement) { console.error("CRITICAL ERROR: 'statusMessage' element not found. Check online-game.html"); criticalElementsMissing = true; }
if (!errorMessageElement) { console.error("CRITICAL ERROR: 'errorMessage' element not found. Check online-game.html"); criticalElementsMissing = true; }
if (!displayGameIdElement) { console.error("CRITICAL ERROR: 'displayGameId' element not found. Check online-game.html"); criticalElementsMissing = true; }
if (!displayPlayerRoleElement) { console.error("CRITICAL ERROR: 'displayPlayerRole' element not found. Check online-game.html"); criticalElementsMissing = true; }
if (!boardDiv) { console.error("CRITICAL ERROR: 'online-game-board-container' div not found. Check online-game.html"); criticalElementsMissing = true; }
if (criticalElementsMissing) {
    console.error("Stopping script execution due to missing critical UI elements.");
    if (errorMessageElement) {
        errorMessageElement.textContent = "Error: Essential game UI elements are missing. Please ensure online-game.html is correctly loaded and IDs match.";
    }
} else {
    console.log("All critical DOM elements referenced successfully.");
}

// Collect interactive buttons for easy enabling/disabling (host/join controls)
const interactiveButtons = [createGameButton, joinGameButton, joinGameIdInput];


// --- Event Listeners for UI Actions ---
console.log("Attempting to attach event listeners to buttons...");
if (createGameButton) {
    createGameButton.addEventListener('click', sendCreateGameMessage);
    console.log("Event listener attached to 'Host New Game' button.");
}
if (joinGameButton) {
    joinGameButton.addEventListener('click', sendJoinGameMessage);
    console.log("Event listener attached to 'Join Game' button.");
}


// --- Initial Connection Attempt ---
connect();

// --- WebSocket Connection Handling Functions ---

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

            // Set heartbeats after successful connection
            stompClient.heartbeat.outgoing = 10000; // Client will send heartbeats every 10 seconds
            stompClient.heartbeat.incoming = 10000; // Client expects heartbeats from server every 10 seconds

            stompClient.subscribe('/user/absc/queue/game.details', (payload) => onGameDetailsReceived(payload), { id: 'gameDetailsSub' });
            stompClient.subscribe('/user/absc/queue/errors', (payload) => onErrorReceived(payload), { id: 'errorQueueSub' });
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
            if (displayGameIdElement) displayGameIdElement.textContent = '';
            if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = '';

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

// --- UI Button Actions (Sending Messages to Server) ---

function sendCreateGameMessage() {
    console.log("sendCreateGameMessage invoked. Checking connection status...");
    if (!isConnected || !stompClient || !stompClient.connected) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Not connected to server. Please wait or refresh.';
            errorMessageElement.style.display = 'block';
        }
        console.warn("Attempted to host game but not connected to WebSocket.");
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

    console.log("Sending STOMP message to /app/game.host", { 'content-type': 'application/json' }, {});
    // Explicitly set content-type header
    stompClient.send("/app/game.host", { 'content-type': 'application/json' }, {});
    console.log("UI updated for Host: PlayerRole set to 0, Game ID placeholder. Waiting for server confirmation.");
}

function sendJoinGameMessage() {
    console.log("sendJoinGameMessage invoked. Checking connection status...");
    if (!isConnected || !stompClient || !stompClient.connected) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Not connected to server. Please wait or refresh.';
            errorMessageElement.style.display = 'block';
        }
        console.warn("Attempted to join game but not connected to WebSocket.");
        return;
    }
    const enteredGameId = joinGameIdInput ? joinGameIdInput.value.trim() : '';
    if (!enteredGameId) {
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Please enter a Game ID to join.';
            errorMessageElement.style.display = 'block';
        }
        console.warn("Attempted to join game but no Game ID entered.");
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

    console.log(`Sending STOMP message to /app/game.join for game: ${enteredGameId}`, { 'content-type': 'application/json' }, JSON.stringify({ 'gameId': enteredGameId }));
    stompClient.send("/app/game.join", { 'content-type': 'application/json' }, JSON.stringify({ 'gameId': enteredGameId }));
    console.log(`UI updated for Joiner: PlayerRole set to 1, Game ID: ${enteredGameId}.`);
}

function disableGameButtons() {
    interactiveButtons.forEach(button => {
        if (button) button.disabled = true;
    });
    console.log("Game options buttons disabled.");
}

function enableGameButtons() {
    interactiveButtons.forEach(button => {
        if (button) button.disabled = false;
    });
    console.log("Game options buttons enabled.");
}

// --- WebSocket Message Handlers (Receiving Messages from Server) ---

function onGameDetailsReceived(payload) {
    console.log('--- onGameDetailsReceived START ---');
    console.log('Raw game details payload received (type:', typeof payload.body, '):', payload.body);

    let receivedState;
    try {
        // Ensure payload.body is a string before parsing
        if (typeof payload.body === 'string') {
            receivedState = JSON.parse(payload.body);
        } else if (payload.body instanceof Object) { // If it's already an object, use directly
            receivedState = payload.body;
            console.warn("Payload body was already an object, skipping JSON.parse.");
        } else {
            console.error("Unexpected payload.body type:", typeof payload.body, "Value:", payload.body);
            throw new Error("Payload body is not a string or object, cannot parse game details.");
        }
        console.log('Parsed Initial Game State (Details):', receivedState);

        if (typeof receivedState.assignedPlayerRole !== 'undefined' && receivedState.assignedPlayerRole !== null) {
            playerRole = receivedState.assignedPlayerRole;
            if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = `You are Player ${playerRole + 1}`;
            console.log("Client: playerRole confirmed by server:", playerRole);
        } else {
            console.warn("Server did not provide 'assignedPlayerRole' in initial details. Using client's initial guess. receivedState:", receivedState);
        }

        if (receivedState.gameId) {
            gameId = receivedState.gameId;
            if (displayGameIdElement) displayGameIdElement.textContent = 'Game ID: ' + gameId;
            console.log("Client: gameId set from details:", gameId);
            ensureTopicSubscription();
        } else {
            console.error("Game details received but gameId is missing. Cannot proceed. receivedState:", receivedState);
            if (errorMessageElement) {
                errorMessageElement.textContent = "Error: Invalid game details received from server (missing Game ID).";
                errorMessageElement.style.display = 'block';
            }
            enableGameButtons();
            if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
            if (gameAreaDiv) gameAreaDiv.style.display = 'none';
            return;
        }

        console.log("Attempting to hide gameOptionsDiv and show gameAreaDiv...");
        if (gameOptionsDiv) {
            gameOptionsDiv.style.display = 'none';
            console.log("gameOptionsDiv display set to 'none'. Current style:", gameOptionsDiv.style.display);
            console.log("Computed style of gameOptionsDiv:", window.getComputedStyle(gameOptionsDiv).display);
        }
        if (gameAreaDiv) {
            gameAreaDiv.style.display = 'block';
            console.log("gameAreaDiv display set to 'block'. Current style:", gameAreaDiv.style.display);
            console.log("Computed style of gameAreaDiv:", window.getComputedStyle(gameAreaDiv).display);
        }
        console.log("UI transition commands sent.");


        if (!currentGameState) {
            currentGameState = receivedState;
        }

        if (receivedState.board && Array.isArray(receivedState.board) && receivedState.board.length === 14) {
            console.log("Calling updateGameBoardUI from onGameDetailsReceived with board data:", receivedState.board);
            updateGameBoardUI(receivedState);
            console.log("Calling updateGameStatusMessage from onGameDetailsReceived.");
            updateGameStatusMessage(receivedState);
            enablePitClicks(receivedState);
            console.log("Initial game state UI updates completed.");
        } else {
            console.warn('Invalid or incomplete initial game board data received from details. Board:', receivedState.board, 'Full State:', receivedState);
            if (errorMessageElement) {
                errorMessageElement.textContent = 'Error: Invalid initial game board data received. Check console for details.';
                errorMessageElement.style.display = 'block';
            }
            enableGameButtons();
            if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
            if (gameAreaDiv) gameAreaDiv.style.display = 'none';
        }
    } catch (e) {
        console.error('Failed to parse initial game details JSON or process payload:', e, 'Payload body:', payload.body);
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Error: Failed to process initial game details (parsing/processing error).';
            errorMessageElement.style.display = 'block';
        }
        enableGameButtons();
        if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
        if (gameAreaDiv) gameAreaDiv.style.display = 'none';
    }
    console.log('--- onGameDetailsReceived END ---');
}

function onGameStateUpdate(payload) {
    console.log('--- onGameStateUpdate START ---');
    console.log('Raw game state update payload received (type:', typeof payload.body, '):', payload.body);

    let gameState;
    try {
        // Ensure payload.body is a string before parsing
        if (typeof payload.body === 'string') {
            gameState = JSON.parse(payload.body);
        } else if (payload.body instanceof Object) { // If it's already an object, use directly
            gameState = payload.body;
            console.warn("Payload body was already an object, skipping JSON.parse.");
        } else {
            console.error("Unexpected payload.body type:", typeof payload.body, "Value:", payload.body);
            throw new Error("Payload body is not a string or object, cannot process game state update.");
        }
        console.log("Client: Game state update received:", gameState);

        if (gameState && Array.isArray(gameState.board) && gameState.board.length === 14) {
            currentGameState = gameState;
            console.log("Calling updateGameBoardUI from onGameStateUpdate with board data:", gameState.board);
            updateGameBoardUI(gameState);
            console.log("Calling updateGameStatusMessage from onGameStateUpdate.");
            updateGameStatusMessage(gameState);
            enablePitClicks(gameState);
            console.log("Game state update UI completed.");
        } else {
            console.warn("Invalid game state received. Board:", gameState.board, 'Full State:', gameState);
            if (errorMessageElement) {
                errorMessageElement.textContent = "Error: Invalid game state received from server. Check console for details.";
                errorMessageElement.style.display = 'block';
            }
        }

        if (gameState.gameStatus === 'FINISHED' || gameState.gameStatus === 'CANCELLED') {
            disablePitClicks();
            if (gameState.gameStatus === 'CANCELLED') {
                if (gameState.winner === -1) {
                    if (statusMessageElement) statusMessageElement.textContent += " Opponent disconnected. Game cancelled.";
                    if (errorMessageElement) {
                        errorMessageElement.textContent = "Game ended abruptly due to opponent leaving.";
                        errorMessageElement.style.display = 'block';
                    }
                }
            }
            setTimeout(() => {
                enableGameButtons();
                gameId = null;
                playerRole = null;
                currentGameState = null;
                if (displayGameIdElement) displayGameIdElement.textContent = '';
                if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = '';
                if (statusMessageElement) statusMessageElement.textContent = '';
                if (errorMessageElement) errorMessageElement.textContent = '';
                if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
                if (gameAreaDiv) gameAreaDiv.style.display = 'none';
                console.log("UI reverted to options after game end/cancellation.");
            }, 5000);
            console.log("Game over/cancelled state handled. Reverting to options in 5s.");
        }

    } catch (e) {
        console.error("Failed to parse game state update JSON or process payload:", e, 'Payload body:', payload.body);
        if (errorMessageElement) {
            errorMessageElement.textContent = "Error: Failed to process game state update (parsing/processing error).";
            errorMessageElement.style.display = 'block';
        }
    }
    console.log('--- onGameStateUpdate END ---');
}

function onErrorReceived(payload) {
    console.log('--- onErrorReceived START ---');
    console.log('Raw error payload received (type:', typeof payload.body, '):', payload.body);
    let error;
    try {
        if (typeof payload.body === 'string') {
            error = JSON.parse(payload.body);
        } else {
            error = payload.body;
        }
        console.error('Error received from server:', error);
        if (errorMessageElement) {
            errorMessageElement.textContent = error.message || 'An unknown error occurred on the server.';
            errorMessageElement.style.display = 'block';
        }
        if (statusMessageElement) statusMessageElement.textContent = 'Error occurred. Please see message above.';

        if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
        if (gameAreaDiv) gameAreaDiv.style.display = 'none';
        enableGameButtons();

        gameId = null;
        playerRole = null;
        currentGameState = null;
        if (displayGameIdElement) displayGameIdElement.textContent = '';
        if (displayPlayerRoleElement) displayPlayerRoleElement.textContent = '';
        if (statusMessageElement) statusMessageElement.textContent = '';

        console.log("Error received from server, UI reverted to options.");
    } catch (e) {
        console.error('Failed to parse error message JSON or process payload:', e, 'Payload body:', payload.body);
        if (errorMessageElement) {
            errorMessageElement.textContent = 'Critical Error: Failed to parse server error message.';
            errorMessageElement.style.display = 'block';
        }
        if (gameOptionsDiv) gameOptionsDiv.style.display = 'block';
        if (gameAreaDiv) gameAreaDiv.style.display = 'none';
        enableGameButtons();
    }
    console.log('--- onErrorReceived END ---');
}


// --- UI Rendering and Interaction Functions ---

function updateGameBoardUI(gameState) {
    console.log("updateGameBoardUI called with gameState:", gameState);
    if (!boardDiv) {
        console.error("Board container element (boardDiv) is null in updateGameBoardUI. Cannot render board. Check HTML ID.");
        return;
    }
    console.log("boardDiv found:", boardDiv); // Log the element itself

    boardDiv.innerHTML = '';
    console.log("Board cleared for re-rendering. innerHTML after clear:", boardDiv.innerHTML);

    const player2PitsRow = document.createElement('div');
    player2PitsRow.className = 'player-pits top-row';
    console.log("Creating Player 2 pits...");
    for (let i = 12; i >= 7; i--) {
        if (i === 13) continue; // Skip Player 2's store
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit';
        const button = document.createElement('button');
        button.textContent = typeof gameState.board[i] === 'number' ? gameState.board[i] : 'ERR';
        button.dataset.index = i;
        button.classList.add('pit-button-element');
        pitDiv.appendChild(button);
        player2PitsRow.appendChild(pitDiv);
        console.log(`  Added P2 Pit ${i} with ${button.textContent} stones.`);
    }
    console.log("Player 2 pits creation complete.");

    const storesRow = document.createElement('div');
    storesRow.className = 'stores-row';
    console.log("Creating stores...");

    const player2Store = createStoreElement(13, typeof gameState.board[13] === 'number' ? gameState.board[13] : 'ERR', 'Player 2 Store');
    storesRow.appendChild(player2Store);
    console.log(`  Added P2 Store (index 13) with ${player2Store.querySelector('.store-stones').textContent} stones.`);

    const spacer = document.createElement('div');
    spacer.className = 'board-spacer';
    storesRow.appendChild(spacer);

    const player1Store = createStoreElement(6, typeof gameState.board[6] === 'number' ? gameState.board[6] : 'ERR', 'Player 1 Store');
    storesRow.appendChild(player1Store);
    console.log(`  Added P1 Store (index 6) with ${player1Store.querySelector('.store-stones').textContent} stones.`);
    console.log("Stores creation complete.");

    const player1PitsRow = document.createElement('div');
    player1PitsRow.className = 'player-pits bottom-row';
    console.log("Creating Player 1 pits...");
    for (let i = 0; i <= 5; i++) {
        // if (i === 6) continue; // No need to skip, loop goes only up to 5
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit';
        const button = document.createElement('button');
        button.textContent = typeof gameState.board[i] === 'number' ? gameState.board[i] : 'ERR';
        button.dataset.index = i;
        button.classList.add('pit-button-element');
        pitDiv.appendChild(button);
        player1PitsRow.appendChild(pitDiv);
        console.log(`  Added P1 Pit ${i} with ${button.textContent} stones.`);
    }
    console.log("Player 1 pits creation complete.");

    boardDiv.appendChild(player2PitsRow);
    boardDiv.appendChild(storesRow);
    boardDiv.appendChild(player1PitsRow);
    console.log("All rows appended to boardDiv. Final boardDiv innerHTML:", boardDiv.innerHTML);


    enablePitClicks(gameState);
    console.log("Board rendered with current game state.");
}

function createStoreElement(storeIndex, stones, label) {
    const storeDiv = document.createElement('div');
    storeDiv.className = 'store';
    storeDiv.innerHTML = `<span class="store-label">${label}</span><span class="store-stones">${stones}</span>`;
    return storeDiv;
}

function updateGameStatusMessage(gameState) {
    console.log("updateGameStatusMessage called with gameState:", gameState);
    if (errorMessageElement) {
        errorMessageElement.textContent = '';
        errorMessageElement.style.display = 'none';
    }

    if (gameState.gameOver) {
        if (gameState.winner === playerRole) {
            if (statusMessageElement) statusMessageElement.textContent = 'Game Over! You won!';
        } else if (gameState.winner === 1 - playerRole) {
            if (statusMessageElement) statusMessageElement.textContent = 'Game Over! You lost.';
        } else {
            if (statusMessageElement) statusMessageElement.textContent = 'Game Over! It\'s a draw!';
        }
        if (statusMessageElement) statusMessageElement.style.color = '#dc3545';
        console.log("Status Message: Game Over. Winner:", gameState.winner);
    } else {
        if (statusMessageElement) statusMessageElement.style.color = '#007bff';
        if (gameState.gameStatus === 'WAITING_FOR_PLAYER') {
            if (statusMessageElement) statusMessageElement.textContent = 'Waiting for opponent to join... Share Game ID: ' + gameId;
            console.log("Status Message: Waiting for player. Game ID:", gameId);
        } else if (gameState.gameStatus === 'IN_PROGRESS') {
            if (statusMessageElement) statusMessageElement.textContent = gameState.currentPlayer === playerRole
                ? 'Your turn.' : 'Opponent\'s turn.';
            console.log("Status Message: In Progress. Current Player:", gameState.currentPlayer, "Your Role:", playerRole);
        } else if (gameState.gameStatus === 'CANCELLED') {
            if (statusMessageElement) statusMessageElement.textContent = 'Game Cancelled. Opponent disconnected.';
            if (errorMessageElement) {
                errorMessageElement.textContent = 'Game ended due to opponent leaving.';
                errorMessageElement.style.display = 'block';
            }
            console.log("Status Message: Game Cancelled.");
        }
    }
}

function enablePitClicks(gameState) {
    console.log("enablePitClicks called with gameState:", gameState);
    disablePitClicks();

    if (gameState.gameOver || gameState.gameStatus === 'CANCELLED') {
        console.log("Pits disabled: Game is over or cancelled.");
        return;
    }

    const pits = boardDiv ? boardDiv.querySelectorAll('.pit-button-element') : [];
    let pitsEnabledCount = 0;
    pits.forEach(button => {
        const index = parseInt(button.dataset.index);

        if (playerRole !== null && playerRole === gameState.currentPlayer && isOwnPit(index, playerRole) && gameState.board[index] > 0) {
            button.disabled = false;
            button.classList.add('active-pit');
            button.onclick = function () {
                makeMove(index);
            };
            pitsEnabledCount++;
        }
    });
    console.log(`Pits enabled: ${pitsEnabledCount} for Player ${playerRole + 1}.`);
}

function disablePitClicks() {
    const pits = boardDiv ? boardDiv.querySelectorAll('.pit-button-element') : [];
    pits.forEach(button => {
        button.disabled = true;
        button.classList.remove('active-pit');
        button.onclick = null;
    });
    console.log("All pits disabled.");
}

function isOwnPit(index, player) {
    return (player === 0 && index >= 0 && index <= 5) ||
        (player === 1 && index >= 7 && index <= 12);
}

function makeMove(pitIndex) {
    if (!stompClient || !stompClient.connected || gameId === null) {
        if (errorMessageElement) {
            errorMessageElement.textContent = "Error: Not connected to game server.";
            errorMessageElement.style.display = 'block';
        }
        console.warn("Cannot make move: Not connected to WebSocket or gameId is null.");
        return;
    }

    disablePitClicks();
    if (statusMessageElement) statusMessageElement.textContent = 'Processing your move... Please wait.';
    console.log(`Attempting to send move for pit ${pitIndex} in game ${gameId}.`);

    stompClient.send("/app/game." + gameId + ".move", {}, JSON.stringify({ 'pitIndex': pitIndex }));
    console.log(`Move sent: Pit ${pitIndex} for Game ID: ${gameId}`);
}

connect();
