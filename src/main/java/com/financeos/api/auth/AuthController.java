package com.financeos.api.auth;

import com.financeos.api.auth.dto.LoginRequest;
import com.financeos.api.auth.dto.SignupRequest;
import com.financeos.api.auth.dto.UserResponse;
import com.financeos.api.auth.dto.GoogleAuthStartResponse;
import com.financeos.core.exception.ValidationException;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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

    /**
     * Completes the SSO flow and returns JSON, matching the contract in
     * `api-spec.yaml`.
     *
     * Google redirects the browser to the *client's* callback page, which calls
     * this from a server action and relays `FINANCEOS_SESSION` onto its own
     * origin — the same hand-off {@link #login} uses. This used to redirect the
     * browser here directly and then 302 to the UI, which set the session cookie
     * on the API's origin instead. That only appeared to work locally, where
     * cookies are shared across ports on `localhost`; anywhere the UI and API sit
     * on different hosts, the UI saw no cookie and bounced to /login.
     */
    @GetMapping("/google/callback")
    public ResponseEntity<UserResponse> handleGoogleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false, name = "error_description") String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (error != null) {
            authService.logGoogleCallbackFailure(state, error, errorDescription);
            throw new ValidationException("Google sign-in failed: " + error);
        }
        if (code == null || code.isBlank()) {
            throw new ValidationException("Missing authorization code");
        }

        User user = authService.handleGoogleLogin(code, state, request, response);
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
