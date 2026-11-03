package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.core.oauth.OAuthStateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * "Fill from Google": incremental OAuth for the birthday scope, then one People API read. Behind
 * {@code financeos.google-birthday.enabled}. The date is never logged.
 */
@Service
public class GoogleBirthdayService {

    public static final String SCOPE = "https://www.googleapis.com/auth/user.birthday.read";

    private final GoogleOAuthClient googleOAuthClient;
    private final OAuthStateService stateService;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String callbackUri;

    public GoogleBirthdayService(GoogleOAuthClient googleOAuthClient, OAuthStateService stateService,
            ProfileService profileService, ObjectMapper objectMapper,
            @Value("${financeos.google-birthday.enabled:false}") boolean enabled,
            @Value("${financeos.google-birthday.redirect-uri:http://localhost:6969/api/v1/me/google-birthday/callback}") String callbackUri) {
        this.googleOAuthClient = googleOAuthClient;
        this.stateService = stateService;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.callbackUri = callbackUri;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String buildAuthorizationUrl(UUID userId) {
        requireEnabled();
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        String state = stateService.issue(OAuthStateService.Purpose.GOOGLE_BIRTHDAY, flowId, userId);
        return googleOAuthClient.buildIncrementalAuthorizationUrl(state, SCOPE, callbackUri);
    }

    /** Returns whether a birth date was saved (false: scope not granted, no birthday, or a typed date exists). */
    public boolean handleCallback(UUID userId, String code, String state) {
        requireEnabled();
        if (!stateService.isValid(OAuthStateService.Purpose.GOOGLE_BIRTHDAY, state, userId)) {
            throw new ValidationException("invalid_state");
        }
        var tokens = googleOAuthClient.exchangeCodeForTokens(code, callbackUri);
        boolean granted = tokens.scope() != null && Arrays.asList(tokens.scope().split("\\s+")).contains(SCOPE);
        if (!granted) {
            return false;
        }
        Optional<String> birthDate = GoogleBirthdayPicker.pick(objectMapper,
                googleOAuthClient.getPeopleBirthdays(tokens.accessToken()));
        return birthDate.isPresent() && profileService.saveGoogleBirthDate(userId, birthDate.get());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ValidationException("Fill from Google is not available");
        }
    }
}
