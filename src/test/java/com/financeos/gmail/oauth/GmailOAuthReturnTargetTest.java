package com.financeos.gmail.oauth;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.OAuthStateService;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gmail connect can come back to Home; the target rides in the signed state and defaults to Settings. */
class GmailOAuthReturnTargetTest {

    private GmailApiClient gmailApiClient;
    private GmailOAuthService service;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gmailApiClient = mock(GmailApiClient.class);
        service = new GmailOAuthService(gmailApiClient, mock(GmailConnectionRepository.class),
                mock(UserRepository.class), new OAuthStateService());
        when(gmailApiClient.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
    }

    private String start(String returnTo) {
        service.buildAuthorizationUrl(alice, returnTo);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(gmailApiClient, org.mockito.Mockito.atLeastOnce()).buildAuthorizationUrl(state.capture());
        return state.getValue();
    }

    @Test
    void homeTargetComesBackAsHome() {
        assertEquals(GmailOAuthService.RETURN_HOME, service.returnTarget(alice, start("home")));
    }

    @Test
    void noTargetAndSettingsDefaultToSettings() {
        assertEquals(GmailOAuthService.RETURN_SETTINGS, service.returnTarget(alice, start(null)));
        assertEquals(GmailOAuthService.RETURN_SETTINGS, service.returnTarget(alice, start("settings")));
    }

    @Test
    void anythingElseIsRefused() {
        assertThrows(ValidationException.class, () -> service.buildAuthorizationUrl(alice, "https://evil.example"));
    }

    @Test
    void aStateForAnotherUserNeverGoesHome() {
        assertEquals(GmailOAuthService.RETURN_SETTINGS, service.returnTarget(UUID.randomUUID(), start("home")));
        assertEquals(GmailOAuthService.RETURN_SETTINGS, service.returnTarget(alice, "abcd1234_home.1.x.forged"));
        assertEquals(GmailOAuthService.RETURN_SETTINGS, service.returnTarget(alice, null));
    }
}
