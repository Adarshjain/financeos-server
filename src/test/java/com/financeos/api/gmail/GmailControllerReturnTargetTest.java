package com.financeos.api.gmail;

import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.oauth.GmailOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The connect callback goes to Settings today and to Home when the flow was started for Home. */
class GmailControllerReturnTargetTest {

    private GmailOAuthService oauth;
    private GmailController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        oauth = mock(GmailOAuthService.class);
        AuthService auth = mock(AuthService.class);
        User user = new User();
        user.setId(userId);
        when(auth.getCurrentUser()).thenReturn(user);
        controller = new GmailController(oauth, auth, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "uiPath", "https://ui.example");
        GmailConnection connection = new GmailConnection();
        connection.setEmail("me@example.com");
        when(oauth.handleCallback(eq(userId), any(), any())).thenReturn(connection);
    }

    @Test
    void homeFlowRedirectsToHome() {
        when(oauth.returnTarget(userId, "s")).thenReturn(GmailOAuthService.RETURN_HOME);
        ResponseEntity<Void> res = controller.handleCallback("code", null, "s");
        assertEquals("https://ui.example/", res.getHeaders().getLocation().toString());
    }

    @Test
    void defaultFlowKeepsTheSettingsRedirect() {
        when(oauth.returnTarget(userId, "s")).thenReturn(GmailOAuthService.RETURN_SETTINGS);
        ResponseEntity<Void> res = controller.handleCallback("code", null, "s");
        assertEquals("https://ui.example/data-sources/connections?gmail=success&email=me%40example.com",
                res.getHeaders().getLocation().toString());
    }

    @Test
    void startPassesTheReturnTarget() {
        when(oauth.buildAuthorizationUrl(userId, "home")).thenReturn("https://g");
        assertEquals("https://g", controller.startOAuth("home").getBody().authorizationUrl());
        verify(oauth).buildAuthorizationUrl(userId, "home");
    }
}
