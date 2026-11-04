package com.tradepulse.tradepulse.service;

import com.tradepulse.tradepulse.dto.AuthResponse;
import com.tradepulse.tradepulse.dto.LoginRequest;
import com.tradepulse.tradepulse.dto.RegisterRequest;
import com.tradepulse.tradepulse.dto.UserProfileResponse;
import com.tradepulse.tradepulse.model.User;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.UserRepository;
import com.tradepulse.tradepulse.security.JwtService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TradingService tradingService;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TradingService tradingService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tradingService = tradingService;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }

        User user = new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), Instant.now());
        try {
            user = userRepository.save(user);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists.", exception);
        }

        Wallet wallet;
        try {
            wallet = tradingService.getOrCreateWallet(user.getId());
        } catch (RuntimeException exception) {
            try {
                userRepository.delete(user);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return createAuthResponse(user, wallet);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        return createAuthResponse(user, tradingService.getOrCreateWallet(user.getId()));
    }

    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found."));
        return profile(user, tradingService.getOrCreateWallet(userId));
    }

    private AuthResponse createAuthResponse(User user, Wallet wallet) {
        return new AuthResponse(jwtService.generateToken(user), profile(user, wallet));
    }

    private UserProfileResponse profile(User user, Wallet wallet) {
        return new UserProfileResponse(user.getId(), user.getEmail(), user.getFullName(),
                wallet.getAvailableBalance());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
