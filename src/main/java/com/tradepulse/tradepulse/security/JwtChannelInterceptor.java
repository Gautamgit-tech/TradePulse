package com.tradepulse.tradepulse.security;

import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public JwtChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            authenticateConnect(accessor);
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor);
        } else if (accessor.getCommand() == StompCommand.SEND) {
            String destination = accessor.getDestination();
            if (destination != null && (destination.startsWith("/topic/") || destination.startsWith("/queue/"))) {
                throw new AccessDeniedException("Clients cannot publish to broker topics.");
            }
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return;
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new AccessDeniedException("Invalid WebSocket authorization header.");
        }
        String token = authorization.substring(7).trim();
        try {
            if (!jwtService.isTokenValid(token)) {
                throw new AccessDeniedException("Invalid or expired WebSocket token.");
            }
            String userId = jwtService.extractUserId(token);
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    userId, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new AccessDeniedException("Invalid or expired WebSocket token.", exception);
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("STOMP subscription destination is required.");
        }
        if (destination.startsWith("/topic/ticks/") || destination.startsWith("/topic/depth/")) {
            return;
        }
        if (destination.startsWith("/topic/orders/")) {
            String userId = destination.substring("/topic/orders/".length());
            if (accessor.getUser() != null && Objects.equals(accessor.getUser().getName(), userId)) {
                return;
            }
            throw new AccessDeniedException("Cannot subscribe to another user's order updates.");
        }
        throw new AccessDeniedException("STOMP subscription destination is not allowed.");
    }
}
