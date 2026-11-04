package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.model.User;
import com.tradepulse.tradepulse.security.JwtChannelInterceptor;
import com.tradepulse.tradepulse.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JwtChannelInterceptorTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private JwtService jwtService;
    private JwtChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET);
        interceptor = new JwtChannelInterceptor(jwtService);
        channel = mock(MessageChannel.class);
    }

    @Test
    void connectTokenEstablishesWebSocketPrincipal() {
        User user = new User("trader@example.com", "hash", "Trader", Instant.now());
        user.setId("user-123");
        String token = jwtService.generateToken(user);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer " + token);

        interceptor.preSend(createMessage(accessor), channel);

        assertNotNull(accessor.getUser());
        assertTrue(accessor.getUser().getName().equals("user-123"));
    }

    @Test
    void authenticatedUserCanSubscribeToOwnOrderUpdates() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/orders/user-123");
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                "user-123", null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        interceptor.preSend(createMessage(accessor), channel);
    }

    @Test
    void usersCannotSubscribeToAnotherUsersOrderUpdates() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/orders/someone-else");
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                "user-123", null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(createMessage(accessor), channel));
    }

    @Test
    void anonymousClientCanSubscribeToPublicMarketData() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/ticks/AAPL");

        interceptor.preSend(createMessage(accessor), channel);
    }

    private Message<byte[]> createMessage(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
