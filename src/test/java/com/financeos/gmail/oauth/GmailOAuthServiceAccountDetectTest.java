package com.financeos.gmail.oauth;

import com.financeos.core.oauth.OAuthStateService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.job.AccountDetectTriggers;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.google.api.services.gmail.model.Profile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Connecting Gmail queues background account detection; disconnecting clears what it stored. */
class GmailOAuthServiceAccountDetectTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void aCompletedConnectHandsTheSavedConnectionToAccountDetect() throws Exception {
        GmailApiClient client = mock(GmailApiClient.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        UserRepository users = mock(UserRepository.class);
        AccountDetectTriggers triggers = mock(AccountDetectTriggers.class);
        OAuthStateService states = new OAuthStateService();
        GmailOAuthService service = new GmailOAuthService(client, connections, users, states, triggers);
        User user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(client.exchangeCodeForTokens("code")).thenReturn(new GmailApiClient.TokenResponse("access", "refresh-made-up", 3600L));
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

        GmailConnection saved = service.handleCallback(userId, "code", state.getValue());

        verify(triggers).onConnected(userId, saved);
        assertEquals(Boolean.TRUE, saved.getIsPrimary());
    }

    @Test
    void disconnectClearsTheStoredFactsOfThatMailbox() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        AccountDetectTriggers triggers = mock(AccountDetectTriggers.class);
        GmailOAuthService service = new GmailOAuthService(mock(GmailApiClient.class), connections, mock(UserRepository.class),
                new OAuthStateService(), triggers);
        GmailConnection c = new GmailConnection();
        UUID connectionId = UUID.randomUUID();
        c.setId(connectionId);
        when(connections.findByUserIdAndIsPrimaryTrue(userId)).thenReturn(Optional.of(c));

        service.disconnect(userId);

        verify(triggers).onDisconnected(eq(userId), eq(connectionId));
    }
}
