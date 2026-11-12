package com.financeos.sms.device;

import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.TooManyAttemptsException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

/**
 * The phone signs in like the web does (email and password, or a Google ID token) and gets a device token. This path
 * never creates a user and never writes to the user row. Failures are counted per account, see {@link SmsLoginLimiter}.
 * Neither passwords nor tokens are ever logged.
 */
@Service
public class SmsDeviceLoginService {

    private static final Logger log = LoggerFactory.getLogger(SmsDeviceLoginService.class);
    static final String BAD_CREDENTIALS = "Invalid email or password";

    private final AuthService auth;
    private final UserRepository users;
    private final SmsDeviceService devices;
    private final SmsLoginLimiter limiter;
    private final GoogleIdTokenCheck google;
    private final String googleClientId;

    public SmsDeviceLoginService(AuthService auth, UserRepository users, SmsDeviceService devices, SmsLoginLimiter limiter,
                                 GoogleIdTokenCheck google, @Value("${google.oauth.client-id:}") String googleClientId) {
        this.auth = auth;
        this.users = users;
        this.devices = devices;
        this.limiter = limiter;
        this.google = google;
        this.googleClientId = googleClientId;
    }

    /** 401 for a wrong password and an unknown email alike; 429 when the email is locked out. */
    public String loginWithPassword(String email, String password, String deviceName, String installId) {
        String key = SmsLoginLimiter.passwordKey(email);
        reserve(key);
        User user;
        try {
            user = auth.verifyCredentials(email, password);
        } catch (AuthenticationException e) {
            outcome("password", "refused", null, "bad-credentials");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        limiter.clear(key);
        String token = devices.createDevice(user.getId(), deviceName, installId);
        outcome("password", "signed-in", user, null);
        return token;
    }

    /** 503 not configured (or Google's keys unreachable), 401 invalid token or unverified email, 404 no such user, 429 locked out. */
    public String loginWithGoogle(String idToken, String deviceName, String installId) {
        if (googleClientId == null || googleClientId.isBlank()) {
            outcome("google", "refused", null, "not-configured");
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Google sign-in is not available");
        }
        GoogleIdTokenCheck.Result result;
        try {
            result = google.check(idToken, googleClientId);
        } catch (GoogleIdTokenCheck.UnavailableException e) {
            outcome("google", "refused", null, "verifier-unavailable");
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Google sign-in is not available");
        }
        String key = SmsLoginLimiter.googleKey(result.subject());
        reserve(key);
        if (!result.valid() || !result.emailVerified() || result.subject() == null || result.email() == null) {
            outcome("google", "refused", null, !result.valid() ? "invalid-token" : "email-not-verified");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google sign-in");
        }
        Optional<User> user = users.findByGoogleId(result.subject());
        if (user.isEmpty()) {
            // an email match only counts for an account that is not tied to another Google account
            user = users.findByEmail(result.email()).filter(u -> u.getGoogleId() == null);
        }
        if (user.isEmpty()) {
            limiter.release(key);
            outcome("google", "refused", null, "no-user");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No FinanceOS account for this Google account");
        }
        limiter.clear(key);
        String token = devices.createDevice(user.get().getId(), deviceName, installId);
        outcome("google", "signed-in", user.get(), null);
        return token;
    }

    private void reserve(String key) {
        Instant now = AppTime.nowInstant();
        if (!limiter.tryAcquire(key, now)) {
            throw new TooManyAttemptsException("Too many attempts. Try again later.", limiter.retryAfterSeconds(key, now));
        }
    }

    private void outcome(String method, String outcome, User user, String reason) {
        String userId = user == null ? "unknown" : user.getId().toString();
        log.info("SMS device sign-in: method={}, outcome={}, userId={}, reason={}", method, outcome, userId,
                reason == null ? "-" : reason,
                StructuredArguments.keyValue("event", "sms.device.login"));
    }
}
