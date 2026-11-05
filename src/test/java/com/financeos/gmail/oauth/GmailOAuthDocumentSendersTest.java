package com.financeos.gmail.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.oauth.OAuthStateService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.google.api.services.gmail.model.Profile;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Adding the NPS document senders on connect is best-effort and runs in its own transaction. */
class GmailOAuthDocumentSendersTest {

    @Test
    void connectSucceedsWhenAddingTheDocumentSendersThrows() throws Exception {
        UUID userId = UUID.randomUUID();
        GmailApiClient client = mock(GmailApiClient.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        SenderAllowlistService senders = mock(SenderAllowlistService.class);
        when(senders.addDocumentSenders(userId)).thenThrow(new IllegalStateException("boom"));
        GmailOAuthService service = new GmailOAuthService(client, connections, users, new OAuthStateService());
        ReflectionTestUtils.setField(service, "senderAllowlistService", senders);
        when(client.exchangeCodeForTokens("code")).thenReturn(
                new GmailApiClient.TokenResponse("at", "refresh-made-up", 3600L, "gmail.readonly"));
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

        assertNotNull(saved);
        verify(senders).addDocumentSenders(userId);
    }

    @Test
    void addDocumentSendersRunsInItsOwnTransactionSoAFailureCannotMarkTheConnectRollbackOnly() throws Exception {
        Transactional tx = SenderAllowlistService.class.getMethod("addDocumentSenders", UUID.class)
                .getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
    }
}
