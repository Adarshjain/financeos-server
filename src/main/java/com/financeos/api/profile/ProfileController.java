package com.financeos.api.profile;

import com.financeos.api.auth.dto.UserResponse;
import com.financeos.core.security.AdminService;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.ProfileService;
import com.financeos.domain.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

/** The signed-in user's profile: Hello and edits. */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    public record HelloRequest(@Nullable String displayName, @Nullable String birthDate,
            @AssertTrue(message = "You need to accept the Terms and Privacy policy") boolean termsAccepted,
            @Nullable Boolean finish) {
    }

    public record UpdateProfileRequest(@Nullable String displayName, @Nullable String birthDate) {
    }

    private final AuthService authService;
    private final ProfileService profileService;
    private final AdminService adminService;

    public ProfileController(AuthService authService, ProfileService profileService,
            AdminService adminService) {
        this.authService = authService;
        this.profileService = profileService;
        this.adminService = adminService;
    }

    @PostMapping("/hello")
    public UserResponse hello(@Valid @RequestBody HelloRequest request) {
        User user = authService.getCurrentUser();
        User saved = profileService.hello(user.getId(), request.displayName(), request.birthDate(), request.termsAccepted(),
                request.finish() == null || request.finish());
        return UserResponse.from(saved, adminService.isAdmin(saved.getId()));
    }

    /** Closes onboarding after a {@code finish=false} hello; idempotent. */
    @PostMapping("/hello/finish")
    public UserResponse finishHello() {
        User user = authService.getCurrentUser();
        User saved = profileService.finishHello(user.getId());
        return UserResponse.from(saved, adminService.isAdmin(saved.getId()));
    }

    @PatchMapping("/profile")
    public UserResponse updateProfile(@RequestBody UpdateProfileRequest request) {
        User user = authService.getCurrentUser();
        User saved = profileService.updateProfile(user.getId(), request.displayName(), request.birthDate());
        return UserResponse.from(saved, adminService.isAdmin(saved.getId()));
    }
}
