package com.financeos.gmail.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.core.oauth.OAuthStateService;
import com.financeos.domain.user.GoogleBirthdayService;
import com.financeos.domain.user.ProfileService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.google.api.services.gmail.model.Profile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Gmail connect callback reads the Google birthday best-effort: a failure never fails the connect. */
class GmailOAuthBirthdayTest {

    private final UUID userId = UUID.randomUUID();

    private GmailConnection connect(GoogleOAuthClient google, ProfileService profiles) throws Exception {
        GmailApiClient client = mock(GmailApiClient.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        GoogleBirthdayService birthday = new GoogleBirthdayService(google, profiles, users, new ObjectMapper());
        OAuthStateService states = new OAuthStateService();
        GmailOAuthService service = new GmailOAuthService(client, connections, users, states, null, birthday);
        when(client.exchangeCodeForTokens("code")).thenReturn(
                new GmailApiClient.TokenResponse("at", "refresh-made-up", 3600L, "gmail.readonly " + GoogleBirthdayService.SCOPE));
        Profile profile = new Profile();
        profile.setEmailAddress("someone@example.test");
        when(client.getProfile(any())).thenReturn(profile);
        when(connections.findByUserIdAndEmail(userId, "someone@example.test")).thenReturn(Optional.empty());
        when(connections.findByUserIdAndIsPrimaryTrue(userId)).thenReturn(Optional.empty());
        when(connections.save(any())).thenAnswer(i -> i.getArgument(0));
        when(client.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
        service.buildAuthorizationUrl(userId);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(client).buildAuthorizationUrl(state.capture());
        return service.handleCallback(userId, "code", state.getValue());
    }

    @Test
    void connectSavesTheGoogleBirthday() throws Exception {
        GoogleOAuthClient google = mock(GoogleOAuthClient.class);
        ProfileService profiles = mock(ProfileService.class);
        when(google.getPeopleBirthdays("at")).thenReturn(
                "{\"birthdays\":[{\"metadata\":{\"source\":{\"type\":\"ACCOUNT\"}},\"date\":{\"year\":1990,\"month\":4,\"day\":5}}]}");
        assertNotNull(connect(google, profiles));
        verify(profiles).saveGoogleBirthDate(userId, "1990-04-05");
    }

    @Test
    void connectSucceedsWhenThePeopleCallThrows() throws Exception {
        GoogleOAuthClient google = mock(GoogleOAuthClient.class);
        when(google.getPeopleBirthdays("at")).thenThrow(new RuntimeException("boom"));
        assertNotNull(connect(google, mock(ProfileService.class)));
    }
}
