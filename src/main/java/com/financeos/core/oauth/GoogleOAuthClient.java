package com.financeos.core.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.auth.dto.GoogleTokenResponse;
import com.financeos.api.auth.dto.GoogleUserInfo;
import com.financeos.core.exception.ValidationException;
import com.google.api.services.gmail.GmailScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Client for handling Google OAuth SSO flow.
 */
@Component
public class GoogleOAuthClient {

    private static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";

    // Combined scopes for SSO and Gmail access
    private static final List<String> SSO_SCOPES = List.of(
            "openid",
            "email",
            "profile",
            GmailScopes.GMAIL_READONLY);

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleOAuthClient(
            @Value("${google.oauth.client-id}") String clientId,
            @Value("${google.oauth.client-secret}") String clientSecret,
            @Value("${google.oauth.redirect-uri}") String redirectUri,
            ObjectMapper objectMapper) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Build Authorization URL for SSO flow.
     */
    public String buildAuthorizationUrl(String state) {
        String scope = String.join(" ", SSO_SCOPES);
        return String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s&access_type=offline&prompt=consent",
                AUTHORIZATION_URL,
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode(scope, StandardCharsets.UTF_8),
                URLEncoder.encode(state, StandardCharsets.UTF_8));
    }

    /**
     * Exchange auth code for tokens.
     */
    public GoogleTokenResponse exchangeCodeForTokens(String code) {
        String requestBody = String.format(
                "code=%s&client_id=%s&client_secret=%s&redirect_uri=%s&grant_type=authorization_code",
                URLEncoder.encode(code, StandardCharsets.UTF_8),
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ValidationException("Failed to exchange code for tokens: " + response.body());
            }
            return objectMapper.readValue(response.body(), GoogleTokenResponse.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error communicating with Google OAuth", e);
        }
    }

    /**
     * Get user profile info using access token.
     */
    public GoogleUserInfo getUserInfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USER_INFO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ValidationException("Failed to get user info: " + response.body());
            }
            return objectMapper.readValue(response.body(), GoogleUserInfo.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error fetching user info from Google", e);
        }
    }
}
