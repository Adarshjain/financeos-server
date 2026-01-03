package com.financeos.api.auth;

import com.financeos.api.auth.dto.LoginRequest;
import com.financeos.api.auth.dto.SignupRequest;
import com.financeos.api.auth.dto.UserResponse;
import com.financeos.api.auth.dto.GoogleAuthStartResponse;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final String uiPath;

    public AuthController(AuthService authService,
            @org.springframework.beans.factory.annotation.Value("${app.ui-path}") String uiPath) {
        this.authService = authService;
        this.uiPath = uiPath;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        User user = authService.login(request, httpRequest, httpResponse);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // Logout is handled by Spring Security's logout handler
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        User user = authService.getCurrentUser();
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @GetMapping("/google/start")
    public ResponseEntity<GoogleAuthStartResponse> startGoogleAuth() {
        String url = authService.generateGoogleAuthUrl();
        return ResponseEntity.ok(new GoogleAuthStartResponse(url));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> handleGoogleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (error != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", uiPath + "/login?error=" + error)
                    .build();
        }

        authService.handleGoogleLogin(code, request, response);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", uiPath)
                .build();
    }
}
