package com.financeos.gmail.client;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

/**
 * Gmail API client wrapper.
 * Handles OAuth credential creation and Gmail API calls.
 */
@Component
public class GmailApiClient {

    private static final String APPLICATION_NAME = "FinanceOS";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
    private static final String TOKEN_SERVER_URL = "https://oauth2.googleapis.com/token";
    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GmailApiClient(
            @Value("${gmail.oauth.client-id:}") String clientId,
            @Value("${gmail.oauth.client-secret:}") String clientSecret,
            @Value("${gmail.oauth.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    /**
     * Build Google OAuth authorization URL.
     */
    public String buildAuthorizationUrl(String state) {
        GoogleAuthorizationCodeFlow flow = createFlow();
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(state)
                .build();
    }

    /**
     * Exchange authorization code for tokens.
     */
    public TokenResponse exchangeCodeForTokens(String code) throws IOException {
        GoogleAuthorizationCodeFlow flow = createFlow();
        GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();

        return new TokenResponse(
                tokenResponse.getAccessToken(),
                tokenResponse.getRefreshToken(),
                tokenResponse.getExpiresInSeconds()
        );
    }

    /**
     * Create Gmail service with refresh token.
     */
    public Gmail createGmailService(String refreshToken) throws IOException {
        Credential credential = createCredential(refreshToken);
        return new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * List messages with pagination.
     */
    public ListMessagesResponse listMessages(Gmail service, String query, String pageToken, Long maxResults) throws IOException {
        Gmail.Users.Messages.List request = service.users().messages().list("me")
                .setQ(query);

        if (pageToken != null) {
            request.setPageToken(pageToken);
        }
        if (maxResults != null) {
            request.setMaxResults(maxResults);
        }

        return request.execute();
    }

    /**
     * Get message by ID.
     */
    public Message getMessage(Gmail service, String messageId) throws IOException {
        return service.users().messages().get("me", messageId)
                .setFormat("full")
                .execute();
    }

    /**
     * Get attachment by ID.
     */
    public byte[] getAttachment(Gmail service, String messageId, String attachmentId) throws IOException {
        MessagePartBody body = service.users().messages().attachments()
                .get("me", messageId, attachmentId)
                .execute();
        return body.decodeData();
    }

    /**
     * Get history (for incremental sync).
     */
    public ListHistoryResponse getHistory(Gmail service, BigInteger startHistoryId, String pageToken, Long maxResults) throws IOException {
        Gmail.Users.History.List request = service.users().history().list("me")
                .setStartHistoryId(startHistoryId);

        if (pageToken != null) {
            request.setPageToken(pageToken);
        }
        if (maxResults != null) {
            request.setMaxResults(maxResults);
        }

        return request.execute();
    }

    /**
     * Get user profile (for email address).
     */
    public Profile getProfile(Gmail service) throws IOException {
        return service.users().getProfile("me").execute();
    }

    private GoogleAuthorizationCodeFlow createFlow() {
        return new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY,
                clientId,
                clientSecret,
                SCOPES)
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
    }

    private Credential createCredential(String refreshToken) {
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(HTTP_TRANSPORT)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl(TOKEN_SERVER_URL))
                .setClientAuthentication(new BasicAuthentication(clientId, clientSecret))
                .build();
        credential.setRefreshToken(refreshToken);
        return credential;
    }

    public record TokenResponse(String accessToken, String refreshToken, Long expiresInSeconds) {}
}
