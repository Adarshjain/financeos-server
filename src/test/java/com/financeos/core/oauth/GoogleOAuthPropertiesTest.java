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
}
