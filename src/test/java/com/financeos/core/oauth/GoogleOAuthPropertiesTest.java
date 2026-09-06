package com.financeos.core.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleOAuthPropertiesTest {

    @Test
    void defaultsAreGoogleProductionEndpoints() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();

        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", props.getAuthorizationUrl());
        assertEquals("https://oauth2.googleapis.com/token", props.getTokenUrl());
        assertEquals("https://www.googleapis.com/oauth2/v2/userinfo", props.getUserInfoUrl());
        assertEquals("https://oauth2.googleapis.com/revoke", props.getRevokeUrl());
    }

    @Test
    void overridesReplaceEveryEndpointIndependently() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setAuthorizationUrl("http://localhost:8089/google/o/oauth2/v2/auth");
        props.setTokenUrl("http://localhost:8089/google/oauth2/token");
        props.setUserInfoUrl("http://localhost:8089/google/oauth2/v2/userinfo");
        props.setRevokeUrl("http://localhost:8089/google/oauth2/revoke");

        assertEquals("http://localhost:8089/google/o/oauth2/v2/auth", props.getAuthorizationUrl());
        assertEquals("http://localhost:8089/google/oauth2/token", props.getTokenUrl());
        assertEquals("http://localhost:8089/google/oauth2/v2/userinfo", props.getUserInfoUrl());
        assertEquals("http://localhost:8089/google/oauth2/revoke", props.getRevokeUrl());
    }
}
