package com.financeos.domain.user;

import com.financeos.api.auth.dto.GoogleTokenResponse;
import com.financeos.api.auth.dto.GoogleUserInfo;
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

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Linking Google to an existing email account overwrites the name, unless the user edited it. */
class AuthServiceNameEditedTest {

    private UserRepository userRepository;
    private OAuthStateService stateService;
    private AuthService authService;
    private GoogleOAuthClient google;

    @BeforeEach
    void setUp() {
        google = mock(GoogleOAuthClient.class);
        userRepository = mock(UserRepository.class);
        stateService = new OAuthStateService();
        authService = new AuthService(mock(AuthenticationManager.class), userRepository, mock(PasswordEncoder.class),
                mock(SecurityContextRepository.class), google, mock(GmailConnectionRepository.class),
                mock(InviteCodeService.class), stateService,
                mock(com.financeos.core.applock.AppLockService.class),
                mock(com.financeos.domain.applock.AppLockDeviceRepository.class));
        when(google.buildAuthorizationUrl(any())).thenReturn("https://accounts.example/consent");
        when(google.exchangeCodeForTokens("code"))
                .thenReturn(new GoogleTokenResponse("at", 3600L, null, "openid", "Bearer", null));
        when(google.getUserInfo("at")).thenReturn(new GoogleUserInfo("gid", "a@example.com", "Google Name", null, true));
        when(userRepository.findByGoogleId("gid")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User linkWith(User existing) {
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.of(existing));
        authService.generateGoogleAuthUrl();
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(google).buildAuthorizationUrl(state.capture());
        return authService.handleGoogleLogin("code", state.getValue(), new MockHttpServletRequest(),
                new MockHttpServletResponse());
    }

    @Test
    void anUneditedNameIsTakenFromGoogle() {
        User existing = new User("a@example.com", "hash");
        existing.setDisplayName("Old");
        assertEquals("Google Name", linkWith(existing).getDisplayName());
    }

    @Test
    void anEditedNameSurvivesGoogleLinking() {
        User existing = new User("a@example.com", "hash");
        existing.setDisplayName("As The Bank Prints It");
        existing.setNameEditedAt(Instant.now());
        assertEquals("As The Bank Prints It", linkWith(existing).getDisplayName());
    }
}
