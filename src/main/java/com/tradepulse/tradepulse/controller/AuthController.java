package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.dto.AuthResponse;
import com.tradepulse.tradepulse.dto.LoginRequest;
import com.tradepulse.tradepulse.dto.RegisterRequest;
import com.tradepulse.tradepulse.dto.UserProfileResponse;
import com.tradepulse.tradepulse.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserProfileResponse me(Principal principal) {
        return authService.getProfile(principal.getName());
    }
}
