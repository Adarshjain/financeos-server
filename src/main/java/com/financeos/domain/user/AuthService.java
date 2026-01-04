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
import com.financeos.core.security.UserContext;

@Service
@Transactional
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
            .getContextHolderStrategy();
    private final GoogleOAuthClient googleOAuthClient;
    private final GmailConnectionRepository gmailConnectionRepository;

    public AuthService(AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            GoogleOAuthClient googleOAuthClient,
            GmailConnectionRepository gmailConnectionRepository) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.googleOAuthClient = googleOAuthClient;
        this.gmailConnectionRepository = gmailConnectionRepository;
    }

    public User signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User with email already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = new User(request.email(), hashedPassword);
        return userRepository.save(user);
    }

    public User login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(request.email(),
                request.password());

        Authentication authentication = authenticationManager.authenticate(token);

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        createSession(authentication, httpRequest, httpResponse, user.getId());

        return user;
    }

    public String generateGoogleAuthUrl() {
        // State should ideally be a random string stored in session to prevent CSRF
        // For simplicity in this iteration, using a static string or simple generation
        String state = java.util.UUID.randomUUID().toString();
        return googleOAuthClient.buildAuthorizationUrl(state);
    }

    public User handleGoogleLogin(String code, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 1. Exchange code for tokens
        var tokenResponse = googleOAuthClient.exchangeCodeForTokens(code);

        // 2. Get user info
        var userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());

        // 3. Find or create user
        User user = userRepository.findByGoogleId(userInfo.id())
                .orElseGet(() -> {
                    // Check if email exists (link account?)
                    // For now, if email exists but no googleId, we could update it, or fail.
                    // Let's simple check email:
                    return userRepository.findByEmail(userInfo.email())
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
                            });
                });

        // 4. Handle Gmail Connection (only if refresh token is present)
        // Note: Google only returns refresh_token on the first time access is granted
        // (consent prompt)
        // OR if prompt=consent is used (which we do in GoogleOAuthClient).
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

        // 5. Login (Create Session)
        // We need a way to authenticate without password.
        // We can use a custom Authentication token or
        // PreAuthenticatedAuthenticationToken.
        // Or simply force authentication into context.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user.getEmail(), null, java.util.Collections.emptyList());
        createSession(auth, httpRequest, httpResponse, user.getId());

        return user;
    }

    private void createSession(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response, UUID userId) {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // TODO: userId should not be null in any case, throw exception if null
        if (userId != null) {
            UserContext.setCurrentUserId(userId);
        }
    }

    // Overload for existing callers if necessary, or just rely on the main method
    // being updated
    private void createSession(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response) {
        createSession(authentication, request, response, null);
    }

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
