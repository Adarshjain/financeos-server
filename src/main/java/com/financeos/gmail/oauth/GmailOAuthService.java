package com.financeos.gmail.oauth;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.OAuthStateService;
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

    /** Callback error when the state is missing, forged, expired or another user's. */
    public static final String INVALID_STATE = "invalid_state";

    private final GmailApiClient gmailApiClient;
    private final GmailConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final OAuthStateService stateService;

    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository,
            OAuthStateService stateService) {
        this.gmailApiClient = gmailApiClient;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.stateService = stateService;
    }

    /**
     * Build OAuth authorization URL. Its state is signed and bound to {@code userId}, so only that
     * user's own callback can complete the flow.
     */
    @Transactional(readOnly = true)
    public String buildAuthorizationUrl(UUID userId) {
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        String state = stateService.issue(OAuthStateService.Purpose.GMAIL_CONNECT, flowId, userId);
        log.info("Gmail OAuth authorize started: flowId={}, userId={}", flowId, userId,
                StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_AUTHORIZE_STARTED),
                StructuredArguments.keyValue("flowId", flowId),
                StructuredArguments.keyValue("userId", userId));
        return gmailApiClient.buildAuthorizationUrl(state);
    }

    /**
     * Handle OAuth callback and store encrypted refresh token. The state must be one this server issued
     * to {@code userId} (see {@link #buildAuthorizationUrl}); otherwise the code is never exchanged and
     * no mailbox is attached.
     */
    public GmailConnection handleCallback(UUID userId, String code, String state) throws IOException {
        int dot = state != null ? state.indexOf('.') : -1;
        String flowId = dot > 0 ? state.substring(0, dot) : UUID.randomUUID().toString().substring(0, 8);
        MDC.put("flowId", flowId);

        try {
            boolean hasCode = code != null && !code.isBlank();
            boolean stateValid = stateService.isValid(OAuthStateService.Purpose.GMAIL_CONNECT, state, userId);
            log.info("Gmail OAuth callback received: flowId={}, hasCode={}, hasError=false, stateValidated={}", flowId, hasCode,
                    stateValid,
                    StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_CALLBACK_RECEIVED),
                    StructuredArguments.keyValue("flowId", flowId),
                    StructuredArguments.keyValue("hasCode", hasCode),
                    StructuredArguments.keyValue("hasError", false),
                    StructuredArguments.keyValue("stateValidated", stateValid));
            if (!stateValid) {
                throw new ValidationException(INVALID_STATE);
            }

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
            // A fresh token repairs a dead mailbox: clear the failure and its reconnect-nag marker.
            connection.setAuthFailedAt(null);
            connection.setReconnectNotifiedAt(null);

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
