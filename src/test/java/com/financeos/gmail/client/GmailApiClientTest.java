package com.financeos.gmail.client;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.gmail.Gmail;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailApiClientTest {

    private static GmailApiClient client(GmailClientProperties endpoints) {
        return new GmailApiClient("cid", "secret", "http://localhost:6969/api/v1/gmail/oauth/callback", endpoints);
    }

    @Test
    void defaultsAreGoogleProductionEndpoints() throws IOException {
        GmailClientProperties props = new GmailClientProperties();
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", props.getOauth().getAuthorizationUrl());
        assertEquals("https://oauth2.googleapis.com/token", props.getOauth().getTokenUrl());
        assertEquals("https://gmail.googleapis.com/", props.getApi().getRootUrl());

        GmailApiClient client = client(props);
        String authUrl = client.buildAuthorizationUrl("state-1");
        assertTrue(authUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), authUrl);

        Gmail gmail = client.createGmailService("rt");
        assertEquals("https://gmail.googleapis.com/", gmail.getRootUrl());
        // This Gmail library revision has an empty service path: every request is built as
        // <rootUrl>gmail/v1/users/{userId}/..., so the root alone decides where calls go.
        assertEquals("https://gmail.googleapis.com/", gmail.getBaseUrl());
        Credential credential = (Credential) gmail.getRequestFactory().getInitializer();
        assertEquals("https://oauth2.googleapis.com/token", credential.getTokenServerEncodedUrl());
        assertEquals("rt", credential.getRefreshToken());
    }

    @Test
    void overridesFlowIntoAuthorizationUrlTokenServerAndApiRoot() throws IOException {
        GmailClientProperties props = new GmailClientProperties();
        props.getOauth().setAuthorizationUrl("http://localhost:8089/google/o/oauth2/v2/auth");
        props.getOauth().setTokenUrl("http://localhost:8089/google/oauth2/token");
        props.getApi().setRootUrl("http://localhost:8089/gmail/");

        GmailApiClient client = client(props);
        String authUrl = client.buildAuthorizationUrl("state-2");
        assertTrue(authUrl.startsWith("http://localhost:8089/google/o/oauth2/v2/auth?"), authUrl);
        assertTrue(authUrl.contains("state=state-2"), authUrl);
        // The Google library does not percent-encode the redirect_uri value.
        assertTrue(authUrl.contains("redirect_uri=http://localhost:6969/api/v1/gmail/oauth/callback"), authUrl);
        assertTrue(authUrl.contains("access_type=offline"), authUrl);
        assertTrue(authUrl.contains("gmail.readonly"), authUrl);

        Gmail gmail = client.createGmailService("rt");
        assertEquals("http://localhost:8089/gmail/", gmail.getRootUrl());
        assertEquals("http://localhost:8089/gmail/", gmail.getBaseUrl());
        assertEquals("http://localhost:8089/gmail/gmail/v1/users/me/profile",
                gmail.users().getProfile("me").buildHttpRequestUrl().build());
        Credential credential = (Credential) gmail.getRequestFactory().getInitializer();
        assertEquals("http://localhost:8089/google/oauth2/token", credential.getTokenServerEncodedUrl());
    }
}
