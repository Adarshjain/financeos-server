package com.financeos.gmail.oauth;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.UUID;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Handles Gmail OAuth flow.
 */
@Service
@Transactional
public class GmailOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuthService.class);

    private final GmailApiClient gmailApiClient;
    private final GmailConnectionRepository connectionRepository;
    private final UserRepository userRepository;

    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository) {
        this.gmailApiClient = gmailApiClient;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Build OAuth authorization URL.
     */
    @Transactional(readOnly = true)
    public String buildAuthorizationUrl(UUID userId) {
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        String state = userId != null ? userId.toString() : flowId;
        log.info("Gmail OAuth authorize started: flowId={}, userId={}", flowId, userId,
                StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_AUTHORIZE_STARTED),
                StructuredArguments.keyValue("flowId", flowId),
                StructuredArguments.keyValue("userId", userId));
        return gmailApiClient.buildAuthorizationUrl(state);
    }

    /**
     * Handle OAuth callback and store encrypted refresh token.
     */
    public GmailConnection handleCallback(UUID userId, String code) throws IOException {
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("flowId", flowId);

        try {
            boolean hasCode = code != null && !code.isBlank();
            // stateValidated=false: The state parameter in Gmail OAuth flow carries correlation context
            // and user identifier but is not verified against a server-side session nonce.
            log.info("Gmail OAuth callback received: flowId={}, hasCode={}, hasError=false, stateValidated=false", flowId, hasCode,
                    StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_CALLBACK_RECEIVED),
                    StructuredArguments.keyValue("flowId", flowId),
                    StructuredArguments.keyValue("hasCode", hasCode),
                    StructuredArguments.keyValue("hasError", false),
                    StructuredArguments.keyValue("stateValidated", false));

            long tokenStart = System.currentTimeMillis();
            GmailApiClient.TokenResponse tokenResponse = gmailApiClient.exchangeCodeForTokens(code);
            long tokenLatency = System.currentTimeMillis() - tokenStart;

            log.info("Gmail OAuth token exchanged: flowId={}, latencyMs={}", flowId, tokenLatency,
                    StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_TOKEN_EXCHANGED),
                    StructuredArguments.keyValue("flowId", flowId),
                    StructuredArguments.keyValue("latencyMs", tokenLatency));

            if (tokenResponse.refreshToken() == null) {
                throw new ValidationException("No refresh token received from Google");
            }

            var gmailService = gmailApiClient.createGmailService(tokenResponse.refreshToken());
            var profile = gmailApiClient.getProfile(gmailService);
            String email = profile.getEmailAddress();

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));

            GmailConnection connection = connectionRepository.findByUserIdAndEmail(userId, email)
                    .orElseGet(() -> {
                        GmailConnection newConn = new GmailConnection();
                        newConn.setUser(user);
                        newConn.setEmail(email);
                        boolean hasPrimary = connectionRepository.findByUserIdAndIsPrimaryTrue(userId).isPresent();
                        newConn.setIsPrimary(!hasPrimary);
                        return newConn;
                    });

            connection.setEncryptedRefreshToken(tokenResponse.refreshToken());
            connection.setIsConnected(true);

            GmailConnection savedConnection = connectionRepository.save(connection);
            log.info("Gmail connection succeeded: email={}, userId={}", email, userId,
                    StructuredArguments.keyValue("event", "oauth.gmail.connected"),
                    StructuredArguments.keyValue("email", email),
                    StructuredArguments.keyValue("userId", userId));
            return savedConnection;
        } catch (Exception e) {
            log.warn("Gmail OAuth callback failed: flowId={}, error={}", flowId, e.getMessage(),
                    StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_CALLBACK_FAILED),
                    StructuredArguments.keyValue("flowId", flowId),
                    StructuredArguments.keyValue("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    e);
            throw e;
        } finally {
            MDC.remove("flowId");
        }
    }

    /**
     * Get connection for user.
     */
    @Transactional(readOnly = true)
    public GmailConnection getConnection(UUID userId) {
        return connectionRepository.findByUserIdAndIsPrimaryTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Gmail connection", userId));
    }

    /**
     * Disconnect Gmail (mark as disconnected).
     */
    public void disconnect(UUID userId) {
        GmailConnection connection = getConnection(userId);
        connection.setIsConnected(false);
        connectionRepository.save(connection);
    }
}
