package com.financeos.api.profile;

import com.financeos.api.auth.dto.UserResponse;
import com.financeos.core.security.AdminService;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.GoogleBirthdayService;
import com.financeos.domain.user.ProfileService;
import com.financeos.domain.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The signed-in user's profile: Hello, edits, and the optional Google birthday fill. */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    public record HelloRequest(@Nullable String displayName, @Nullable String birthDate,
            @AssertTrue(message = "You need to accept the Terms and Privacy policy") boolean termsAccepted) {
    }

    public record UpdateProfileRequest(@Nullable String displayName, @Nullable String birthDate) {
    }

    public record GoogleBirthdayStartResponse(String url) {
    }

    private final AuthService authService;
    private final ProfileService profileService;
    private final GoogleBirthdayService googleBirthdayService;
    private final AdminService adminService;

    @org.springframework.beans.factory.annotation.Value("${app.ui-path:http://localhost:3001}")
    private String uiPath;

    public ProfileController(AuthService authService, ProfileService profileService,
            GoogleBirthdayService googleBirthdayService, AdminService adminService) {
        this.authService = authService;
        this.profileService = profileService;
        this.googleBirthdayService = googleBirthdayService;
        this.adminService = adminService;
    }

    @PostMapping("/hello")
    public UserResponse hello(@Valid @RequestBody HelloRequest request) {
        User user = authService.getCurrentUser();
        User saved = profileService.hello(user.getId(), request.displayName(), request.birthDate(), request.termsAccepted());
        return UserResponse.from(saved, adminService.isAdmin(saved.getId()));
    }

    @PatchMapping("/profile")
    public UserResponse updateProfile(@RequestBody UpdateProfileRequest request) {
        User user = authService.getCurrentUser();
        User saved = profileService.updateProfile(user.getId(), request.displayName(), request.birthDate());
        return UserResponse.from(saved, adminService.isAdmin(saved.getId()));
    }

    @GetMapping("/google-birthday/start")
    public GoogleBirthdayStartResponse startGoogleBirthday() {
        User user = authService.getCurrentUser();
        return new GoogleBirthdayStartResponse(googleBirthdayService.buildAuthorizationUrl(user.getId()));
    }

    /** Google sends the browser here; whatever happens, it ends back on the client's Hello page. */
    @GetMapping("/google-birthday/callback")
    public ResponseEntity<Void> googleBirthdayCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        String result = "failed";
        if (error == null && code != null && !code.isBlank()) {
            try {
                User user = authService.getCurrentUser();
                result = googleBirthdayService.handleCallback(user.getId(), code, state) ? "filled" : "none";
            } catch (RuntimeException e) {
                result = "failed";
            }
        }
        String target = uiPath + "/hello?birthday=" + URLEncoder.encode(result, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
