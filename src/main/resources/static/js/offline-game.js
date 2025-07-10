// src/main/resources/static/js/offline-game.js
document.addEventListener('DOMContentLoaded', () => {
    // DOM elements
    const statusMessageElement = document.getElementById('status-message');
    const gameBoardContainer = document.getElementById('game-board-container');
    const errorMessageElement = document.getElementById('error-message');
    const newGameForm = document.getElementById('newGameForm');

    // Initial game state and error message passed from Thymeleaf (from game.html)
    let currentGameState = window.initialGame;
    let currentErrorMessage = window.initialErrorMessage;

    // Attach event listener for the "New Game" form submission
    if (newGameForm) {
        newGameForm.addEventListener('submit', (event) => {
            // No custom logic needed here, just let the form submit normally.
            // Spring's @SessionStatus.setComplete() will handle starting a new game.
            console.log('New game button clicked, submitting form to start a new offline game.');
        });
    }

    /**
     * Renders the Mancala board based on the provided game state.
     * It rebuilds the board structure dynamically for each update.
     * @param {object} gameState - The current state of the game board and metadata.
     */
    function renderBoard(gameState) {
        // Basic validation for the game state object
        if (!gameState || !gameState.board || gameState.board.length !== 14) {
            console.error("Invalid game state received for rendering:", gameState);
            gameBoardContainer.innerHTML = '<p>Error: Could not render game board. Invalid state received.</p>';
            return;
        }

        // Clear existing board content before re-rendering to ensure fresh state
        gameBoardContainer.innerHTML = '';

        // --- Create Player 2's pits row (indices 12 down to 7) ---
        const player2PitsRow = document.createElement('div');
        player2PitsRow.className = 'player-pits top-row'; // Use 'player-pits' and 'top-row' as defined in CSS
        for (let i = 12; i >= 7; i--) {
            if (i === 13) continue; // Skip Player 2's store (it's handled separately)
            // Create a button for each pit, wrapped in a form for POST submission
            const pitButton = createPitButton(
                i,
                gameState.board[i],
                // Pit is clickable if it's Player 2's turn, game is not over, and pit has stones
                gameState.currentPlayer === 1 && !gameState.gameOver && gameState.board[i] > 0
            );
            player2PitsRow.appendChild(pitButton);
        }

        // --- Create the central row for stores ---
        const storesRow = document.createElement('div');
        storesRow.className = 'stores-row'; // Custom class for horizontal arrangement of stores

        // Player 2's store (index 13) - typically on the left for Player 2
        const player2Store = createStoreElement(13, gameState.board[13], 'Player 2 Store');
        storesRow.appendChild(player2Store);

        // Add a flexible spacer to push stores to the ends, helping align with pit rows
        const spacer = document.createElement('div');
        spacer.className = 'board-spacer'; // Custom class for styling the space
        storesRow.appendChild(spacer);

        // Player 1's store (index 6) - typically on the right for Player 1
        const player1Store = createStoreElement(6, gameState.board[6], 'Player 1 Store');
        storesRow.appendChild(player1Store);


        // --- Create Player 1's pits row (indices 0 up to 5) ---
        const player1PitsRow = document.createElement('div');
        player1PitsRow.className = 'player-pits bottom-row'; // Use 'player-pits' and 'bottom-row' as defined in CSS
        for (let i = 0; i <= 5; i++) {
            if (i === 6) continue; // Skip Player 1's store (it's handled separately)
            // Create a button for each pit, wrapped in a form for POST submission
            const pitButton = createPitButton(
                i,
                gameState.board[i],
                // Pit is clickable if it's Player 1's turn, game is not over, and pit has stones
                gameState.currentPlayer === 0 && !gameState.gameOver && gameState.board[i] > 0
            );
            player1PitsRow.appendChild(pitButton);
        }

        // Append all rows to the main game board container in the correct visual order
        gameBoardContainer.appendChild(player2PitsRow);
        gameBoardContainer.appendChild(storesRow);
        gameBoardContainer.appendChild(player1PitsRow);

        // --- Update turn/game over status message ---
        updateStatusMessage(gameState);

        // --- Display server-side error messages, if any ---
        if (currentErrorMessage) {
            errorMessageElement.textContent = currentErrorMessage;
            errorMessageElement.style.display = 'block';
            currentErrorMessage = null; // Clear the message after displaying it to prevent re-display
        } else {
            errorMessageElement.textContent = '';
            errorMessageElement.style.display = 'none';
        }
    }

    /**
     * Creates an HTML button element for a pit, wrapped within a form for server submission.
     * @param {number} pitIndex - The index of the pit (0-13).
     * @param {number} stones - The number of stones currently in the pit.
     * @param {boolean} isClickable - Determines if the pit button should be enabled and styled as active.
     * @returns {HTMLElement} The pit container div, holding the form and button.
     */
    function createPitButton(pitIndex, stones, isClickable) {
        const pitDiv = document.createElement('div');
        pitDiv.className = 'pit'; // Use 'pit' class from CSS for overall pit styling

        // Wrap the button in a form to allow POST submission for the move
        const form = document.createElement('form');
        form.action = '/move'; // Endpoint for offline game moves
        form.method = 'post';

        const button = document.createElement('button');
        button.type = 'submit'; // This button will trigger the form submission
        button.name = 'pitIndex'; // Name of the request parameter expected by Spring Controller
        button.value = pitIndex;  // Value of the pitIndex parameter
        button.textContent = stones; // Display number of stones
        button.classList.add('pit-button-element'); // Specific class for styling the button itself

        // Disable the button if it's not clickable for the current turn/game state
        if (!isClickable) {
            button.disabled = true;
        } else {
            button.classList.add('active-pit'); // Add class to highlight active pits
        }

        form.appendChild(button);
        pitDiv.appendChild(form); // Append the form (containing the button) to the pit div
        return pitDiv;
    }

    /**
     * Creates an HTML div element for a Mancala store.
     * @param {number} storeIndex - The index of the store (6 for Player 1, 13 for Player 2).
     * @param {number} stones - The number of stones in the store.
     * @param {string} label - The text label for the store (e.g., 'Player 1 Store').
     * @returns {HTMLElement} The store div element.
     */
    function createStoreElement(storeIndex, stones, label) {
        const storeDiv = document.createElement('div');
        storeDiv.className = 'store'; // Use 'store' class from CSS
        // Uses innerHTML for simplicity to set label and stone count
        storeDiv.innerHTML = `<span class="store-label">${label}</span><span class="store-stones">${stones}</span>`;
        return storeDiv;
    }

    /**
     * Updates the status message displayed to the user based on the current game state.
     * @param {object} gameState - The current state of the game.
     */
    function updateStatusMessage(gameState) {
        if (gameState.gameOver) {
            let winnerText = '';
            if (gameState.winner === 0) {
                winnerText = 'Player 1 wins!';
            } else if (gameState.winner === 1) {
                winnerText = 'Player 2 wins!';
            } else {
                winnerText = 'It\'s a draw!';
            }
            statusMessageElement.textContent = `Game Over! ${winnerText}`;
            statusMessageElement.style.color = '#dc3545'; // Set color for game over message
        } else {
            statusMessageElement.textContent = `It's Player ${gameState.currentPlayer + 1}'s turn.`;
            statusMessageElement.style.color = '#007bff'; // Set color for turn message
        }
    }

    // Initial board render when the page loads with data from Thymeleaf
    // This ensures the board is displayed correctly on page load or after a redirect.
    if (currentGameState) {
        renderBoard(currentGameState);
    } else {
        // Fallback if no game state is provided (shouldn't happen with @SessionAttributes)
        statusMessageElement.textContent = 'Error: Game state not initialized from server.';
        statusMessageElement.style.color = '#dc3545';
        errorMessageElement.textContent = 'Please try starting a new game or check server logs.';
        errorMessageElement.style.display = 'block';
    }
});
