package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.auth.dto.GoogleTokenResponse;
import com.financeos.api.auth.dto.GoogleUserInfo;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.core.oauth.OAuthStateService;
import com.financeos.core.security.InviteCodeService;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Google sign-in reads the Google birthday best-effort: a failure never fails the sign-in. */
class AuthServiceBirthdayTest {

    private User signIn(GoogleOAuthClient google, ProfileService profiles) {
        UserRepository users = mock(UserRepository.class);
        OAuthStateService states = new OAuthStateService();
        GoogleBirthdayService birthday = new GoogleBirthdayService(google, profiles, users, new ObjectMapper(), true);
        AuthService auth = new AuthService(mock(AuthenticationManager.class), users, mock(PasswordEncoder.class),
                mock(SecurityContextRepository.class), google, mock(GmailConnectionRepository.class),
                mock(InviteCodeService.class), states, mock(com.financeos.core.applock.AppLockService.class),
                mock(com.financeos.domain.applock.AppLockDeviceRepository.class), birthday);
        UUID id = UUID.randomUUID();
        User existing = new User("a@example.com", "hash");
        existing.setId(id);
        when(google.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
        when(google.getRedirectUri()).thenReturn("http://localhost/cb");
        when(google.getSsoScopes()).thenReturn(java.util.List.of("openid"));
        when(google.exchangeCodeForTokens("code")).thenReturn(
                new GoogleTokenResponse("at", 3600L, null, "openid " + GoogleBirthdayService.SCOPE, "Bearer", null));
        when(google.getUserInfo("at")).thenReturn(new GoogleUserInfo("gid", "a@example.com", "N", null, true));
        when(users.findByGoogleId("gid")).thenReturn(Optional.of(existing));
        when(users.findById(id)).thenReturn(Optional.of(existing));
        auth.generateGoogleAuthUrl();
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(google).buildAuthorizationUrl(state.capture());
        return auth.handleGoogleLogin("code", state.getValue(), new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void signInSavesTheGoogleBirthday() {
        GoogleOAuthClient google = mock(GoogleOAuthClient.class);
        ProfileService profiles = mock(ProfileService.class);
        when(google.getPeopleBirthdays("at")).thenReturn(
                "{\"birthdays\":[{\"metadata\":{\"source\":{\"type\":\"ACCOUNT\"}},\"date\":{\"year\":1990,\"month\":4,\"day\":5}}]}");
        assertEquals("a@example.com", signIn(google, profiles).getEmail());
        verify(profiles).saveGoogleBirthDate(any(), org.mockito.ArgumentMatchers.eq("1990-04-05"));
    }

    @Test
    void signInSucceedsWhenThePeopleCallThrows() {
        GoogleOAuthClient google = mock(GoogleOAuthClient.class);
        when(google.getPeopleBirthdays("at")).thenThrow(new RuntimeException("boom"));
        assertEquals("a@example.com", signIn(google, mock(ProfileService.class)).getEmail());
    }
}
