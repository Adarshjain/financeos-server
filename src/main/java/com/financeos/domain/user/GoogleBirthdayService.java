package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.oauth.GoogleOAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Fills the birth date from Google right after a sign-in or Gmail connect that asked for the
 * birthday scope. Best effort: never throws; the date is never logged.
 */
@Service
public class GoogleBirthdayService {

    private static final Logger log = LoggerFactory.getLogger(GoogleBirthdayService.class);

    public static final String SCOPE = "https://www.googleapis.com/auth/user.birthday.read";

    private final GoogleOAuthClient googleOAuthClient;
    private final ProfileService profileService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public GoogleBirthdayService(GoogleOAuthClient googleOAuthClient, ProfileService profileService,
            UserRepository userRepository, ObjectMapper objectMapper) {
        this.googleOAuthClient = googleOAuthClient;
        this.profileService = profileService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /** Returns whether a birth date was saved (false: scope not granted, date already set, none found, or failed). */
    public boolean fillFromGoogle(UUID userId, String accessToken, @Nullable String grantedScopes) {
        try {
            if (grantedScopes == null || !Arrays.asList(grantedScopes.split("\\s+")).contains(SCOPE)) {
                return false;
            }
            boolean hasDate = userRepository.findById(userId).map(u -> u.getBirthDate() != null).orElse(true);
            if (hasDate) {
                return false;
            }
            Optional<String> birthDate = GoogleBirthdayPicker.pick(objectMapper,
                    googleOAuthClient.getPeopleBirthdays(accessToken));
            if (birthDate.isEmpty()) {
                return false;
            }
            // validated here, outside ProfileService's transaction: an invalid Google date (30 Feb, year 1800, future)
            // throwing inside it would mark the caller's sign-in / Gmail-connect transaction rollback-only
            String normalized = BirthDates.normalize(birthDate.get());
            return profileService.saveGoogleBirthDate(userId, normalized);
        } catch (Exception e) {
            log.warn("Google birthday fill skipped: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
