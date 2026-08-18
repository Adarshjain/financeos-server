package com.financeos.domain.user;

import com.financeos.api.auth.dto.LoginRequest;
import com.financeos.api.auth.dto.SignupRequest;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.InviteCodeService;
import com.financeos.core.security.UserContext;
import com.financeos.core.security.SessionHashUtils;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
            .getContextHolderStrategy();
    private final GoogleOAuthClient googleOAuthClient;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final InviteCodeService inviteCodeService;

    public AuthService(AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            GoogleOAuthClient googleOAuthClient,
            GmailConnectionRepository gmailConnectionRepository,
            InviteCodeService inviteCodeService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.googleOAuthClient = googleOAuthClient;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.inviteCodeService = inviteCodeService;
    }

    public User signup(SignupRequest request) {
        inviteCodeService.assertValid(request.inviteCode());
        if (userRepository.existsByEmail(request.email())) {
            log.warn("Signup rejected: email={}, reason=duplicate-email", request.email(),
                    StructuredArguments.keyValue("event", Events.AUTH_SIGNUP_REJECTED),
                    StructuredArguments.keyValue("email", request.email()),
                    StructuredArguments.keyValue("reason", "duplicate-email"));
            throw new DuplicateResourceException("User with email already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = new User(request.email(), hashedPassword);
        User savedUser = userRepository.save(user);
        log.info("Signup succeeded: email={}", savedUser.getEmail(),
                StructuredArguments.keyValue("event", Events.AUTH_SIGNUP_SUCCEEDED),
                StructuredArguments.keyValue("email", savedUser.getEmail()));
        return savedUser;
    }

    public User login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(request.email(),
                request.password());

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(token);
        } catch (AuthenticationException ae) {
            String reason = "auth-failed";
            if (ae instanceof BadCredentialsException) {
                reason = "bad-password";
            } else if (ae instanceof UsernameNotFoundException || ae instanceof InternalAuthenticationServiceException) {
                reason = "unknown-email";
            }
            log.warn("Login failed: email={}, reason={}", request.email(), reason,
                    StructuredArguments.keyValue("event", Events.AUTH_LOGIN_FAILED),
                    StructuredArguments.keyValue("email", request.email()),
                    StructuredArguments.keyValue("reason", reason));
            throw ae;
        }

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        createSession(authentication, httpRequest, httpResponse, user.getId());

        log.info("Login succeeded: email={}", user.getEmail(),
                StructuredArguments.keyValue("event", Events.AUTH_LOGIN_SUCCEEDED),
                StructuredArguments.keyValue("email", user.getEmail()));

        return user;
    }

    public String generateGoogleAuthUrl() {
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        // Note: state carries flowId prefix followed by random UUID for CSRF correlation entropy
        String state = flowId + "." + UUID.randomUUID();
        String redirectUri = googleOAuthClient.getRedirectUri();
        String scopes = String.join(" ", googleOAuthClient.getSsoScopes());

        log.info("Google OAuth authorize started: flowId={}, redirectUri={}", flowId, redirectUri,
                StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_AUTHORIZE_STARTED),
                StructuredArguments.keyValue("flowId", flowId),
                StructuredArguments.keyValue("redirectUri", redirectUri),
                StructuredArguments.keyValue("scopes", scopes));

        return googleOAuthClient.buildAuthorizationUrl(state);
    }

    public void logGoogleCallbackFailure(String state, String error, String errorDescription) {
        String flowId = parseFlowId(state);
        String redirectUri = googleOAuthClient.getRedirectUri();
        log.warn("Google OAuth callback failed: flowId={}, error={}, redirectUri={}", flowId, error, redirectUri,
                StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_CALLBACK_FAILED),
                StructuredArguments.keyValue("flowId", flowId),
                StructuredArguments.keyValue("error", error != null ? error : "unknown_error"),
                StructuredArguments.keyValue("errorDescription", errorDescription != null ? errorDescription : ""),
                StructuredArguments.keyValue("redirectUri", redirectUri));
    }

    private String parseFlowId(String state) {
        if (state == null || state.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        int dotIdx = state.indexOf('.');
        return dotIdx > 0 ? state.substring(0, dotIdx) : state;
    }

    public User handleGoogleLogin(String code, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return handleGoogleLogin(code, null, httpRequest, httpResponse);
    }

    public User handleGoogleLogin(String code, String state, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String effectiveFlowId = parseFlowId(state);
        MDC.put("flowId", effectiveFlowId);

        try {
            boolean hasCode = code != null && !code.isBlank();
            // Note: state is carried for correlation only (flowId) and is not verified against session state
            log.info("Google OAuth callback received: flowId={}, hasCode={}, hasError=false, stateValidated=false", effectiveFlowId, hasCode,
                    StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_CALLBACK_RECEIVED),
                    StructuredArguments.keyValue("flowId", effectiveFlowId),
                    StructuredArguments.keyValue("hasCode", hasCode),
                    StructuredArguments.keyValue("hasError", false),
                    StructuredArguments.keyValue("stateValidated", false));

            long startTokenTime = System.currentTimeMillis();
            var tokenResponse = googleOAuthClient.exchangeCodeForTokens(code);
            long tokenLatency = System.currentTimeMillis() - startTokenTime;

            log.info("Google OAuth token exchanged: flowId={}, latencyMs={}", effectiveFlowId, tokenLatency,
                    StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_TOKEN_EXCHANGED),
                    StructuredArguments.keyValue("flowId", effectiveFlowId),
                    StructuredArguments.keyValue("latencyMs", tokenLatency),
                    StructuredArguments.keyValue("grantedScopes", tokenResponse.scope() != null ? tokenResponse.scope() : ""));

            var userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());

            User user = userRepository.findByGoogleId(userInfo.id())
                    .orElseGet(() -> userRepository.findByEmail(userInfo.email())
                            .map(existingUser -> {
                                existingUser.setGoogleId(userInfo.id());
                                existingUser.setDisplayName(userInfo.name());
                                existingUser.setPictureUrl(userInfo.pictureUrl());
                                return userRepository.save(existingUser);
                            })
                            .orElseGet(() -> {
                                User newUser = new User(
                                        userInfo.email(),
                                        userInfo.id(),
                                        userInfo.name(),
                                        userInfo.pictureUrl());
                                return userRepository.save(newUser);
                            }));

            if (tokenResponse.refreshToken() != null) {
                GmailConnection connection = gmailConnectionRepository.findByUserIdAndIsPrimaryTrue(user.getId())
                        .orElse(new GmailConnection());

                connection.setUser(user);
                connection.setEmail(userInfo.email());
                connection.setEncryptedRefreshToken(tokenResponse.refreshToken());
                connection.setIsConnected(true);
                connection.setIsPrimary(true);

                gmailConnectionRepository.save(connection);
            }

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    user.getEmail(), null, java.util.Collections.emptyList());
            createSession(auth, httpRequest, httpResponse, user.getId());

            return user;
        } catch (Exception e) {
            log.warn("Google OAuth callback failed: flowId={}, error={}, redirectUri={}", effectiveFlowId, e.getMessage(), googleOAuthClient.getRedirectUri(),
                    StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_CALLBACK_FAILED),
                    StructuredArguments.keyValue("flowId", effectiveFlowId),
                    StructuredArguments.keyValue("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    StructuredArguments.keyValue("redirectUri", googleOAuthClient.getRedirectUri()),
                    e);
            throw e;
        } finally {
            MDC.remove("flowId");
        }
    }

    private void createSession(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response, UUID userId) {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        if (userId != null) {
            UserContext.setCurrentUserId(userId);
        }

        String rawSessionId = (request != null && request.getSession(false) != null) ? request.getSession(false).getId() : null;
        String sessionIdHash = SessionHashUtils.hashSessionId(rawSessionId);
        log.info("Session created: sessionIdHash={}", sessionIdHash,
                StructuredArguments.keyValue("event", Events.AUTH_SESSION_CREATED),
                StructuredArguments.keyValue("sessionIdHash", sessionIdHash));
    }

    // Overload for existing callers if necessary, or just rely on the main method
    // being updated

    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("User", "current");
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", email));
    }
}
