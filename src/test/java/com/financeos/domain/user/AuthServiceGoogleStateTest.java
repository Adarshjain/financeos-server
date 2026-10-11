package com.financeos.domain.user;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.oauth.GoogleOAuthClient;
import com.financeos.core.oauth.OAuthStateService;
import com.financeos.core.security.InviteCodeService;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Google sign-in exchanges the code only for a fresh sign-in state this server issued; any other state
 * is rejected before Google is called, so no user is created or signed in.
 */
class AuthServiceGoogleStateTest {

    private GoogleOAuthClient googleOAuthClient;
    private UserRepository userRepository;
    private OAuthStateService stateService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        googleOAuthClient = mock(GoogleOAuthClient.class);
        userRepository = mock(UserRepository.class);
        stateService = new OAuthStateService();
        authService = new AuthService(mock(AuthenticationManager.class), userRepository, mock(PasswordEncoder.class),
                mock(SecurityContextRepository.class), googleOAuthClient, mock(GmailConnectionRepository.class),
                mock(InviteCodeService.class), stateService);
        when(googleOAuthClient.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
        // The exchange failing is enough to show it was reached.
        when(googleOAuthClient.exchangeCodeForTokens("code")).thenThrow(new IllegalStateException("exchange reached"));
    }

    @Test
    void theStateFromStartGetsTheCodeExchanged() {
        authService.generateGoogleAuthUrl();
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(googleOAuthClient).buildAuthorizationUrl(state.capture());

        IllegalStateException reached = assertThrows(IllegalStateException.class, () -> callback(state.getValue()));

        assertEquals("exchange reached", reached.getMessage());
    }

    @Test
    void aStateThisServerNeverIssuedIsRejectedBeforeTheExchange() {
        assertRejected("abcd1234." + java.util.UUID.randomUUID());
    }

    @Test
    void aMissingStateIsRejectedBeforeTheExchange() {
        assertRejected(null);
    }

    @Test
    void aGmailConnectStateIsNotASignInState() {
        assertRejected(stateService.issue(OAuthStateService.Purpose.GMAIL_CONNECT, "abcd1234", null));
    }

    private void assertRejected(String state) {
        ValidationException e = assertThrows(ValidationException.class, () -> callback(state));
        assertEquals(AuthService.INVALID_STATE_MESSAGE, e.getMessage());
        verify(googleOAuthClient, never()).exchangeCodeForTokens(any());
        verify(userRepository, never()).save(any());
    }

    private void callback(String state) {
        authService.handleGoogleLogin("code", state, new MockHttpServletRequest(), new MockHttpServletResponse());
    }
}
