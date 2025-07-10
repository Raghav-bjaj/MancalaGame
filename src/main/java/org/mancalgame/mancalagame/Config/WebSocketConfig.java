package org.mancalgame.mancalagame.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuration for Spring's WebSocket Message Broker using STOMP.
 * Enables scheduling for background tasks like cleaning up stale games.
 */
@Configuration
@EnableWebSocketMessageBroker // Enables WebSocket message handling, backed by a message broker
@EnableScheduling           // Enables Spring's scheduled task execution (e.g., for cleanup)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Registers STOMP endpoints that clients will use to connect to the WebSocket server.
     * @param registry The registry for STOMP endpoints.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Registers the "/ws" endpoint, enabling SockJS fallback options.
        // SockJS is used for browsers that do not support native WebSockets.
        registry.addEndpoint("/ws").withSockJS();
    }

    /**
     * Configures the message broker options.
     * @param registry The message broker registry.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enables a simple in-memory message broker.
        // Messages destined for "/topic" will be broadcast to all subscribed clients.
        // Messages destined for "/queue" will be sent to a specific user (point-to-point).
        registry.enableSimpleBroker("/topic", "/queue");

        // Sets the application destination prefix.
        // Messages from clients to the server (e.g., to @MessageMapping methods)
        // must be prefixed with "/app". For example, a client sends to "/app/game.host".
        registry.setApplicationDestinationPrefixes("/app");
    }
}
