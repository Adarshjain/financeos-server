package com.financeos.core.security;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class InviteCodeService {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeService.class);

    private final AppConfigProperties appConfigProperties;
    private final InviteAttemptLimiter limiter;

    public InviteCodeService(AppConfigProperties appConfigProperties, InviteAttemptLimiter limiter) {
        this.appConfigProperties = appConfigProperties;
        this.limiter = limiter;
    }

    @PostConstruct
    public void init() {
        String configured = appConfigProperties.getInvite().getCode();
        boolean isConfigured = configured != null && !configured.isBlank();
        log.info("Invite code configured: {}", isConfigured);

        if (isConfigured && configured.trim().length() < 12) {
            log.warn("Configured invite code is shorter than 12 characters");
        }
    }

    public void assertValid(String submitted) {
        limiter.assertNotLockedOut();

        String configured = appConfigProperties.getInvite().getCode();
        if (configured == null || configured.isBlank()) {
            log.error("Signup rejected: reason=invite-code-not-configured",
                    StructuredArguments.keyValue("event", Events.AUTH_SIGNUP_REJECTED),
                    StructuredArguments.keyValue("reason", "invite-code-not-configured"));
            throw new ValidationException("Signups are currently closed");
        }

        String submittedTrimmed = submitted != null ? submitted.trim() : "";
        byte[] submittedHash = sha256(submittedTrimmed);
        byte[] configuredHash = sha256(configured.trim());

        if (!MessageDigest.isEqual(submittedHash, configuredHash)) {
            limiter.recordFailure();
            log.warn("Signup rejected: reason=invalid-invite-code",
                    StructuredArguments.keyValue("event", Events.AUTH_SIGNUP_REJECTED),
                    StructuredArguments.keyValue("reason", "invalid-invite-code"));
            throw new ValidationException("Invalid invite code");
        }

        limiter.reset();
    }

    private byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
