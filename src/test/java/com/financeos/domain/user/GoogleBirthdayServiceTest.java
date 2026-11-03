package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.auth.dto.GoogleTokenResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.core.oauth.OAuthStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleBirthdayServiceTest {

    private static final String PEOPLE = """
            {"birthdays":[{"metadata":{"source":{"type":"ACCOUNT"}},"date":{"year":1990,"month":4,"day":5}}]}""";

    private final UUID userId = UUID.randomUUID();
    private GoogleOAuthClient google;
    private ProfileService profiles;
    private GoogleBirthdayService service;
    private String state;

    @BeforeEach
    void setUp() {
        google = mock(GoogleOAuthClient.class);
        profiles = mock(ProfileService.class);
        service = new GoogleBirthdayService(google, new OAuthStateService(), profiles, new ObjectMapper(), true,
                "http://localhost/cb");
        when(google.buildIncrementalAuthorizationUrl(anyString(), anyString(), anyString())).thenReturn("https://g");
        service.buildAuthorizationUrl(userId);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(google).buildIncrementalAuthorizationUrl(captor.capture(), org.mockito.ArgumentMatchers.eq(GoogleBirthdayService.SCOPE), anyString());
        state = captor.getValue();
        when(google.getPeopleBirthdays("at")).thenReturn(PEOPLE);
    }

    private void granted(String scope) {
        when(google.exchangeCodeForTokens("code", "http://localhost/cb"))
                .thenReturn(new GoogleTokenResponse("at", 3600L, null, scope, "Bearer", null));
    }

    @Test
    void grantedScopeSavesThePickedDate() {
        granted("openid " + GoogleBirthdayService.SCOPE);
        when(profiles.saveGoogleBirthDate(userId, "1990-04-05")).thenReturn(true);
        assertTrue(service.handleCallback(userId, "code", state));
    }

    @Test
    void aDeniedScopeNeverCallsThePeopleApi() {
        granted("openid email");
        assertFalse(service.handleCallback(userId, "code", state));
        verify(google, never()).getPeopleBirthdays(any());
    }

    @Test
    void anotherUsersStateIsRejected() {
        assertThrows(ValidationException.class, () -> service.handleCallback(UUID.randomUUID(), "code", state));
        verify(google, never()).exchangeCodeForTokens(any(), any());
    }

    @Test
    void disabledMeansNoFlow() {
        GoogleBirthdayService off = new GoogleBirthdayService(google, new OAuthStateService(), profiles,
                new ObjectMapper(), false, "http://localhost/cb");
        assertThrows(ValidationException.class, () -> off.buildAuthorizationUrl(userId));
    }
}
