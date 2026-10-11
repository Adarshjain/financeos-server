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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Gmail connect state is issued to the user who started the flow, and the callback exchanges the
 * code only for that user's own state: a forged, missing or other user's state stops before Google
 * is called and attaches no mailbox.
 */
class GmailOAuthServiceStateTest {

    private GmailApiClient gmailApiClient;
    private GmailConnectionRepository connectionRepository;
    private OAuthStateService stateService;
    private GmailOAuthService service;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        gmailApiClient = mock(GmailApiClient.class);
        connectionRepository = mock(GmailConnectionRepository.class);
        stateService = new OAuthStateService();
        service = new GmailOAuthService(gmailApiClient, connectionRepository, mock(UserRepository.class), stateService);
        when(gmailApiClient.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
        // A token without a refresh token ends the callback right after the exchange.
        when(gmailApiClient.exchangeCodeForTokens("code"))
                .thenReturn(new GmailApiClient.TokenResponse("access", null, 3600L));
    }

    @Test
    void myOwnStateGetsTheCodeExchanged() throws Exception {
        String state = startFlowFor(alice);

        ValidationException afterExchange = assertThrows(ValidationException.class,
                () -> service.handleCallback(alice, "code", state));

        assertEquals("No refresh token received from Google", afterExchange.getMessage());
        verify(gmailApiClient).exchangeCodeForTokens("code");
    }

    @Test
    void anotherUsersStateIsRejectedBeforeTheExchange() throws Exception {
        String bobsState = startFlowFor(bob);

        assertInvalidState(() -> service.handleCallback(alice, "code", bobsState));
    }

    @Test
    void aMissingStateIsRejectedBeforeTheExchange() throws Exception {
        assertInvalidState(() -> service.handleCallback(alice, "code", null));
    }

    @Test
    void theOldBareUserIdStateIsRejected() throws Exception {
        assertInvalidState(() -> service.handleCallback(alice, "code", alice.toString()));
    }

    @Test
    void aSignInStateIsNotAConnectState() throws Exception {
        String signIn = stateService.issue(OAuthStateService.Purpose.GOOGLE_SSO, "abcd1234", alice);

        assertInvalidState(() -> service.handleCallback(alice, "code", signIn));
    }

    @Test
    void aStateSignedByAnotherServerIsRejected() throws Exception {
        String foreign = new OAuthStateService().issue(OAuthStateService.Purpose.GMAIL_CONNECT, "abcd1234", alice);

        assertInvalidState(() -> service.handleCallback(alice, "code", foreign));
    }

    private String startFlowFor(UUID userId) {
        service.buildAuthorizationUrl(userId);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(gmailApiClient, org.mockito.Mockito.atLeastOnce()).buildAuthorizationUrl(state.capture());
        return state.getValue();
    }

    private void assertInvalidState(org.junit.jupiter.api.function.Executable callback) throws Exception {
        ValidationException e = assertThrows(ValidationException.class, callback);
        assertEquals(GmailOAuthService.INVALID_STATE, e.getMessage());
        verify(gmailApiClient, never()).exchangeCodeForTokens(any());
        verify(connectionRepository, never()).save(any());
    }
}
