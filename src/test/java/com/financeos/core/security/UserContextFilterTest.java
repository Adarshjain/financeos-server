package com.financeos.core.security;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter names the signed-in user for the request (which turns the per-user Hibernate filter on).
 * A signed-in session whose user row is gone is ended and the request runs signed out, never as
 * "no current user" with the session still authenticated.
 */
class UserContextFilterTest {

    private UserRepository userRepository;
    private UserContextFilter filter;
    private MockHttpServletRequest request;
    private MockHttpSession session;

    /** What the rest of the chain saw. */
    private final AtomicReference<UUID> seenUserId = new AtomicReference<>();
    private final AtomicBoolean seenAuthenticated = new AtomicBoolean();
    private final AtomicBoolean chainRan = new AtomicBoolean();
    private final FilterChain chain = (req, res) -> {
        chainRan.set(true);
        seenUserId.set(UserContext.getCurrentUserId());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        seenAuthenticated.set(auth != null && auth.isAuthenticated());
    };

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        filter = new UserContextFilter(userRepository);
        request = new MockHttpServletRequest("GET", "/api/v1/categories");
        session = new MockHttpSession();
        request.setSession(session);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        UserContext.clear();
    }

    @Test
    void aSignedInUserIsTheCurrentUserForTheRequestAndClearedAfter() throws Exception {
        User alice = new User();
        alice.setId(UUID.randomUUID());
        when(userRepository.findByEmail("alice@example.test")).thenReturn(Optional.of(alice));
        signIn("alice@example.test");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(alice.getId(), seenUserId.get());
        assertTrue(seenAuthenticated.get());
        assertFalse(session.isInvalid());
        assertNull(UserContext.getCurrentUserId(), "cleared once the request is done");
    }

    @Test
    void aSessionWhoseUserIsGoneIsEndedAndTheRequestRunsSignedOut() throws Exception {
        when(userRepository.findByEmail("deleted@example.test")).thenReturn(Optional.empty());
        signIn("deleted@example.test");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(chainRan.get(), "the chain still runs, so security answers 401 for protected routes");
        assertNull(seenUserId.get());
        assertFalse(seenAuthenticated.get(), "no authenticated principal is left for the rest of the chain");
        assertTrue(session.isInvalid(), "the orphaned session is invalidated");
    }

    @Test
    void anOrphanedPrincipalWithoutASessionIsStillSignedOut() throws Exception {
        request.setSession(null);
        MockHttpServletRequest sessionless = new MockHttpServletRequest("GET", "/api/v1/categories");
        when(userRepository.findByEmail("deleted@example.test")).thenReturn(Optional.empty());
        signIn("deleted@example.test");

        filter.doFilter(sessionless, new MockHttpServletResponse(), chain);

        assertTrue(chainRan.get());
        assertNull(seenUserId.get());
        assertFalse(seenAuthenticated.get());
        assertNull(sessionless.getSession(false), "no session is created for it");
    }

    @Test
    void anAnonymousRequestIsLeftAlone() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(chainRan.get());
        assertNull(seenUserId.get());
        assertFalse(session.isInvalid());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void aRequestWithNoAuthenticationIsLeftAlone() throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(chainRan.get());
        assertNull(seenUserId.get());
        assertFalse(session.isInvalid());
        verify(userRepository, never()).findByEmail(any());
    }

    private static void signIn(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }
}
