package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.dto.RegisterRequest;
import com.tradepulse.tradepulse.model.User;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.UserRepository;
import com.tradepulse.tradepulse.security.JwtService;
import com.tradepulse.tradepulse.service.AuthService;
import com.tradepulse.tradepulse.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private TradingService tradingService;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tradingService = mock(TradingService.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(userRepository, passwordEncoder, tradingService, jwtService);
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndInitializesPrivateWallet() {
        when(userRepository.findByEmail("trader@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password-123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId("user-123");
            return saved;
        });
        when(tradingService.getOrCreateWallet("user-123")).thenReturn(
                new Wallet("user-123", "INR", new BigDecimal("100000.00"), BigDecimal.ZERO));
        when(jwtService.generateToken(any(User.class))).thenReturn("signed.jwt.token");

        var response = authService.register(new RegisterRequest(
                "Test Trader", " Trader@Example.com ", "password-123"));

        assertEquals("signed.jwt.token", response.token());
        assertEquals("user-123", response.user().id());
        assertEquals("trader@example.com", response.user().email());
        assertEquals("Test Trader", response.user().fullName());
        assertEquals(new BigDecimal("100000.00"), response.user().availableCash());
        verify(tradingService).getOrCreateWallet("user-123");
    }

    @Test
    void duplicateEmailReturnsConflict() {
        when(userRepository.findByEmail("trader@example.com"))
                .thenReturn(Optional.of(new User()));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.register(new RegisterRequest(
                        "Test Trader", "trader@example.com", "password-123")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void duplicateKeyRaceReturnsConflict() {
        when(userRepository.findByEmail("trader@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password-123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.register(new RegisterRequest(
                        "Test Trader", "trader@example.com", "password-123")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }
}
