package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.model.User;
import com.tradepulse.tradepulse.security.JwtService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    @Test
    void generatedTokenIsSignedAndContainsTheUserIdSubject() {
        JwtService jwtService = new JwtService(TEST_SECRET);
        User user = new User("trader@example.com", "hash", "Test Trader", Instant.now());
        user.setId("user-123");

        String token = jwtService.generateToken(user);

        assertTrue(jwtService.isTokenValid(token));
        assertEquals("user-123", jwtService.extractUserId(token));
        assertFalse(jwtService.isTokenValid(token + "tampered"));
    }
}
