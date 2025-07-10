package org.mancalgame.mancalagame;

import org.mancalgame.mancalagame.Service.MancalaGameService;
import org.mancalgame.mancalagame.game.MancalaGame;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;

/**
 * Spring MVC Controller for managing the offline (session-based) Mancala game.
 * Uses @SessionAttributes to store the MancalaGame instance in the HTTP session.
 */
@Controller
@SessionAttributes("mancalaGame") // Stores the "mancalaGame" attribute in the HTTP session
public class MancalaGameController {

    private final MancalaGameService gameService;

    public MancalaGameController(MancalaGameService gameService) {
        this.gameService = gameService;
    }

    /**
     * Handles requests to the root URL, showing the main menu.
     * @return The name of the main menu Thymeleaf template.
     */
    @GetMapping("/")
    public String showMainMenu() {
        return "main-menu";
    }

    /**
     * Creates or retrieves a MancalaGame instance for the session.
     * This method is called before any @RequestMapping methods if "mancalaGame" is not in the session.
     * @return A new MancalaGame instance.
     */
    @ModelAttribute("mancalaGame")
    public MancalaGame setupGame() {
        System.out.println("DEBUG: Creating/Retrieving MancalaGame instance for session.");
        return gameService.createNewGame();
    }

    /**
     * Displays the offline game board.
     * @param game The MancalaGame instance from the session.
     * @param model The Spring Model to pass data to the view.
     * @return The name of the game Thymeleaf template.
     */
    @GetMapping("/play-offline")
    public String playOffline(@ModelAttribute("mancalaGame") MancalaGame game, Model model) {
        model.addAttribute("game", game); // Pass the game object to the Thymeleaf template
        updateOfflineGameStatus(game, model); // Update status message based on game state
        return "game"; // Returns the Thymeleaf template name
    }

    /**
     * Handles a player's move in the offline game.
     * @param pitIndex The index of the pit selected by the player.
     * @param game The MancalaGame instance from the session.
     * @param model The Spring Model to pass data to the view.
     * @return A redirect to /play-offline to refresh the board, or "game" if an error occurred.
     */
    @PostMapping("/move")
    public String makeMove(@RequestParam int pitIndex, @ModelAttribute("mancalaGame") MancalaGame game, Model model) {
        try {
            gameService.makeMove(game, pitIndex); // Execute the move using the game service
            model.addAttribute("errorMessage", null); // Clear any previous errors on success
        } catch (IllegalArgumentException e) {
            // Catch specific invalid move exceptions
            model.addAttribute("errorMessage", e.getMessage());
            // Stay on the same page to immediately show the error without redirecting
            model.addAttribute("game", game); // Re-add game to model for re-rendering current state
            updateOfflineGameStatus(game, model); // Update status messages
            return "game";
        } catch (IllegalStateException e) {
            // Catch unexpected game state issues (e.g., game instance is null, though unlikely with @SessionAttributes)
            model.addAttribute("errorMessage", "Game error: " + e.getMessage());
            model.addAttribute("game", game);
            updateOfflineGameStatus(game, model);
            return "game";
        }
        // Redirect after successful POST to prevent double submission (POST-REDIRECT-GET pattern)
        return "redirect:/play-offline";
    }

    /**
     * Resets the current offline game, starting a new one.
     * @param sessionStatus Allows invalidating the current session's MancalaGame.
     * @return A redirect to /play-offline, which will trigger setupGame() to create a new game.
     */
    @PostMapping("/newGame")
    public String newGame(SessionStatus sessionStatus) {
        sessionStatus.setComplete(); // Invalidates the "mancalaGame" object from the session
        return "redirect:/play-offline"; // Redirects to start a fresh game
    }

    /**
     * Displays options for playing online. This method now serves the main online game page.
     * The host/join functionality is handled by JavaScript on the client-side using WebSockets.
     * @return The name of the online game Thymeleaf template.
     */
    @GetMapping("/online-options") // This is the route linked from main-menu.html
    public String showOnlineOptions() {
        // This will now directly load the online-game.html, which contains the host/join UI
        // and the WebSocket logic.
        return "online-options";
    }

    /**
     * Helper method to add appropriate status messages to the model for offline play.
     * @param game The current MancalaGame instance.
     * @param model The Spring Model.
     */
    private void updateOfflineGameStatus(MancalaGame game, Model model) {
        if (game.isGameOver()) {
            String winnerMessage;
            if (game.getWinner() == 0) {
                winnerMessage = "Game Over! Player 1 wins!";
            } else if (game.getWinner() == 1) {
                winnerMessage = "Game Over! Player 2 wins!";
            } else {
                winnerMessage = "Game Over! It's a draw!";
            }
            model.addAttribute("statusMessage", winnerMessage);
        } else {
            model.addAttribute("statusMessage", "It's Player " + (game.getCurrentPlayer() + 1) + "'s turn.");
        }
    }
}
