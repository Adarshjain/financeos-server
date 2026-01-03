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

/**
 * Handles Gmail OAuth flow.
 */
@Service
@Transactional
public class GmailOAuthService {

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
        // Use userId as state to verify callback
        return gmailApiClient.buildAuthorizationUrl(userId.toString());
    }

    /**
     * Handle OAuth callback and store encrypted refresh token.
     */
    public GmailConnection handleCallback(UUID userId, String code) throws IOException {
        // Exchange code for tokens
        GmailApiClient.TokenResponse tokenResponse = gmailApiClient.exchangeCodeForTokens(code);

        if (tokenResponse.refreshToken() == null) {
            throw new ValidationException("No refresh token received from Google");
        }

        // Get user's Gmail email
        var gmailService = gmailApiClient.createGmailService(tokenResponse.refreshToken());
        var profile = gmailApiClient.getProfile(gmailService);
        String email = profile.getEmailAddress();

        // Fetch user entity
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Find or create connection
        // Check if we already have a connection for this email
        GmailConnection connection = connectionRepository.findByUserIdAndEmail(userId, email)
                .orElseGet(() -> {
                    // Start new connection
                    GmailConnection newConn = new GmailConnection();
                    newConn.setUser(user);
                    newConn.setEmail(email);
                    // Determine if this should be primary (if no primary exists)
                    boolean hasPrimary = connectionRepository.findByUserIdAndIsPrimaryTrue(userId).isPresent();
                    newConn.setIsPrimary(!hasPrimary);
                    return newConn;
                });

        connection.setEncryptedRefreshToken(tokenResponse.refreshToken());
        connection.setIsConnected(true);

        return connectionRepository.save(connection);
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
