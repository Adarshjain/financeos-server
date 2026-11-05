package com.financeos.gmail.oauth;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.OAuthStateService;
import com.financeos.domain.user.GoogleBirthdayService;
import com.financeos.domain.user.ProfileService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.job.AccountDetectTriggers;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** Background account detection: enqueued on connect, cleared on disconnect (null in unit tests). */
    private final AccountDetectTriggers accountDetectTriggers;
    /** Optional birth date fill (null in unit tests). */
    private final GoogleBirthdayService googleBirthdayService;
    /** Marks onboarding done when connecting from the Welcome flow (null in unit tests). */
    private final ProfileService profileService;
    /** Adds the NPS (document) senders on connect; null in unit tests. */
    @Autowired(required = false)
    private com.financeos.gmail.ingest.SenderAllowlistService senderAllowlistService;

    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository,
            OAuthStateService stateService) {
        this(gmailApiClient, connectionRepository, userRepository, stateService, null);
    }

    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository,
            OAuthStateService stateService,
            AccountDetectTriggers accountDetectTriggers) {
        this(gmailApiClient, connectionRepository, userRepository, stateService, accountDetectTriggers, null);
    }

    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository,
            OAuthStateService stateService,
            AccountDetectTriggers accountDetectTriggers,
            GoogleBirthdayService googleBirthdayService) {
        this(gmailApiClient, connectionRepository, userRepository, stateService, accountDetectTriggers, googleBirthdayService, null);
    }

    @Autowired
    public GmailOAuthService(GmailApiClient gmailApiClient,
            GmailConnectionRepository connectionRepository,
            UserRepository userRepository,
            OAuthStateService stateService,
            AccountDetectTriggers accountDetectTriggers,
            GoogleBirthdayService googleBirthdayService,
            ProfileService profileService) {
        this.profileService = profileService;
        this.googleBirthdayService = googleBirthdayService;
        this.gmailApiClient = gmailApiClient;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.stateService = stateService;
        this.accountDetectTriggers = accountDetectTriggers;
    }

    /**
     * Build OAuth authorization URL. Its state is signed and bound to {@code userId}, so only that
     * user's own callback can complete the flow.
     */
    @Transactional(readOnly = true)
    public String buildAuthorizationUrl(UUID userId) {
        return buildAuthorizationUrl(userId, null);
    }

    /** Where the callback sends the browser after connecting. */
    public static final String RETURN_SETTINGS = "settings";
    public static final String RETURN_HOME = "home";
    private static final String HOME_FLOW_SUFFIX = "_home";

    /**
     * Like {@link #buildAuthorizationUrl(UUID)} with a return target: {@code home} or {@code settings}
     * (the default). The target rides in the signed flow id, so a callback can't be pointed elsewhere.
     */
    @Transactional(readOnly = true)
    public String buildAuthorizationUrl(UUID userId, @org.springframework.lang.Nullable String returnTo) {
        if (returnTo != null && !returnTo.isBlank() && !RETURN_HOME.equals(returnTo) && !RETURN_SETTINGS.equals(returnTo)) {
            throw new ValidationException("returnTo must be home or settings");
        }
        String flowId = UUID.randomUUID().toString().substring(0, 8)
                + (RETURN_HOME.equals(returnTo) ? HOME_FLOW_SUFFIX : "");
        String state = stateService.issue(OAuthStateService.Purpose.GMAIL_CONNECT, flowId, userId);
        log.info("Gmail OAuth authorize started: flowId={}, userId={}", flowId, userId,
                StructuredArguments.keyValue("event", Events.OAUTH_GMAIL_AUTHORIZE_STARTED),
                StructuredArguments.keyValue("flowId", flowId),
                StructuredArguments.keyValue("userId", userId));
        return gmailApiClient.buildAuthorizationUrl(state);
    }

    /** The return target of a state this server issued to {@code userId}; settings for anything else. */
    public String returnTarget(UUID userId, String state) {
        if (!stateService.isValid(OAuthStateService.Purpose.GMAIL_CONNECT, state, userId)) {
            return RETURN_SETTINGS;
        }
        String flowId = state.substring(0, state.indexOf('.'));
        return flowId.endsWith(HOME_FLOW_SUFFIX) ? RETURN_HOME : RETURN_SETTINGS;
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
            if (senderAllowlistService != null) {
                try {
                    senderAllowlistService.addDocumentSenders(userId);
                } catch (RuntimeException e) {
                    // The mailbox is connected either way; the senders come with the next connect.
                    log.warn("Adding the document senders failed: {}", e.getClass().getSimpleName());
                }
            }
            // Connecting from Welcome finishes onboarding. Its HelloCompletedEvent queues the one detection run after
            // commit, so onConnected is skipped then; when hello was already done onConnected queues it as before.
            boolean helloJustDone = flowId.endsWith(HOME_FLOW_SUFFIX) && profileService != null && profileService.completeHello(userId);
            if (!helloJustDone && accountDetectTriggers != null) {
                accountDetectTriggers.onConnected(userId, savedConnection);
            }
            log.info("Gmail connection succeeded: email={}, userId={}", email, userId,
                    StructuredArguments.keyValue("event", "oauth.gmail.connected"),
                    StructuredArguments.keyValue("email", email),
                    StructuredArguments.keyValue("userId", userId));
            if (googleBirthdayService != null) {
                googleBirthdayService.fillFromGoogle(userId, tokenResponse.accessToken(), tokenResponse.scope());
            }
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
        if (accountDetectTriggers != null) {
            accountDetectTriggers.onDisconnected(userId, connection.getId());
        }
    }
}
