package com.financeos.domain.user;

import com.financeos.api.auth.dto.DeleteAccountRequest;
import com.financeos.api.auth.dto.DeletionSummaryResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.security.AccountDeletionLimiter;
import com.financeos.domain.job.JobService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private AccountDeletionLimiter accountDeletionLimiter;

    @Mock
    private AccountDeletionExecutor executor;

    @Mock
    private JobService jobService;

    @Mock
    private GmailConnectionRepository gmailConnectionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FindByIndexNameSessionRepository<Session> sessionRepository;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpSession httpSession;

    private AccountDeletionService accountDeletionService;

    private User passwordUser;
    private User googleUser;
    private UUID passwordUserId;
    private UUID googleUserId;

    @BeforeEach
    void setUp() {
        accountDeletionService = new AccountDeletionService(
                authService,
                accountDeletionLimiter,
                executor,
                jobService,
                gmailConnectionRepository,
                passwordEncoder,
                sessionRepository
        );

        passwordUserId = UUID.randomUUID();
        passwordUser = new User();
        passwordUser.setId(passwordUserId);
        passwordUser.setEmail("user@example.com");
        passwordUser.setPasswordHash("$2a$12$hashedPassword");

        googleUserId = UUID.randomUUID();
        googleUser = new User();
        googleUser.setId(googleUserId);
        googleUser.setEmail("sso@example.com");
        googleUser.setPasswordHash(null);
    }

    @Test
    void deleteCurrentUser_wrongPassword_throws403AndRecordsFailure() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);
        when(passwordEncoder.matches("wrongpass", "$2a$12$hashedPassword")).thenReturn(false);

        ApiStatusException ex = assertThrows(ApiStatusException.class, () ->
                accountDeletionService.deleteCurrentUser(new DeleteAccountRequest("wrongpass", null), httpRequest)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(AccountDeletionService.CODE_FORBIDDEN, ex.getCode());
        verify(accountDeletionLimiter).recordFailure(passwordUserId);
        verify(executor, never()).deleteUserAndVerify(any());
    }

    @Test
    void deleteCurrentUser_nullBody_throws403WithoutDeleting() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);

        ApiStatusException ex = assertThrows(ApiStatusException.class, () ->
                accountDeletionService.deleteCurrentUser(null, httpRequest)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(executor, never()).deleteUserAndVerify(any());
    }

    @Test
    void deleteCurrentUser_googleUser_wrongEmail_throws403AndRecordsFailure() {
        when(authService.getCurrentUser()).thenReturn(googleUser);

        ApiStatusException ex = assertThrows(ApiStatusException.class, () ->
                accountDeletionService.deleteCurrentUser(new DeleteAccountRequest(null, "wrong@example.com"), httpRequest)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(accountDeletionLimiter).recordFailure(googleUserId);
        verify(executor, never()).deleteUserAndVerify(any());
    }

    @Test
    void deleteCurrentUser_googleUser_matchingEmail_proceeds() {
        when(authService.getCurrentUser()).thenReturn(googleUser);
        when(jobService.countRunningJobs(googleUserId)).thenReturn(0L);
        when(executor.countRowsForUser(googleUserId)).thenReturn(Map.of("accounts", 3L));
        when(httpRequest.getSession(false)).thenReturn(httpSession);

        Session mockSession = mock(Session.class);
        when(sessionRepository.findByPrincipalName("sso@example.com")).thenReturn(Map.of("sess-1", mockSession));

        // Case-insensitive and whitespace-tolerant: a pasted address must still match.
        accountDeletionService.deleteCurrentUser(new DeleteAccountRequest(null, "  SSO@Example.com "), httpRequest);

        verify(accountDeletionLimiter).reset(googleUserId);
        verify(jobService).requestCancelAllUserJobs(googleUserId);
        verify(executor).deleteUserAndVerify(googleUserId);
        verify(sessionRepository).deleteById("sess-1");
        verify(httpSession).invalidate();
    }

    @Test
    void deleteCurrentUser_jobsNotDrained_throws409BusyWithClientCode() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);
        when(passwordEncoder.matches("secret", "$2a$12$hashedPassword")).thenReturn(true);
        when(jobService.countRunningJobs(passwordUserId)).thenReturn(1L);

        ApiStatusException ex = assertThrows(ApiStatusException.class, () ->
                accountDeletionService.deleteCurrentUser(new DeleteAccountRequest("secret", null), httpRequest)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(AccountDeletionService.CODE_BUSY, ex.getCode());
        // The message is what the user reads, so it must not be the code itself.
        assertNotEquals(AccountDeletionService.CODE_BUSY, ex.getMessage());
        verify(jobService).requestCancelAllUserJobs(passwordUserId);
        verify(executor, never()).deleteUserAndVerify(any());
    }

    @Test
    void deleteCurrentUser_googleRevokeFails_stillCompletesDeletion() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);
        when(passwordEncoder.matches("secret", "$2a$12$hashedPassword")).thenReturn(true);
        when(jobService.countRunningJobs(passwordUserId)).thenReturn(0L);
        when(executor.countRowsForUser(passwordUserId)).thenReturn(Map.of());
        when(gmailConnectionRepository.findByUserId(passwordUserId))
                .thenThrow(new RuntimeException("gmail lookup exploded"));

        assertDoesNotThrow(() ->
                accountDeletionService.deleteCurrentUser(new DeleteAccountRequest("secret", null), httpRequest));

        verify(executor).deleteUserAndVerify(passwordUserId);
    }

    @Test
    void deleteCurrentUser_verificationFails_doesNotDestroySessions() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);
        when(passwordEncoder.matches("secret", "$2a$12$hashedPassword")).thenReturn(true);
        when(jobService.countRunningJobs(passwordUserId)).thenReturn(0L);
        when(executor.countRowsForUser(passwordUserId)).thenReturn(Map.of("accounts", 1L));
        doThrow(new IllegalStateException("Account deletion verification failed: table FNO_TRADES"))
                .when(executor).deleteUserAndVerify(passwordUserId);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                accountDeletionService.deleteCurrentUser(new DeleteAccountRequest("secret", null), httpRequest)
        );

        assertTrue(ex.getMessage().contains("verification failed"));
        // The transaction rolled back, so the account still exists — its sessions must live.
        verify(sessionRepository, never()).deleteById(anyString());
    }

    @Test
    void deleteCurrentUser_revokesEveryGmailConnectionBeforeDeleting() {
        when(authService.getCurrentUser()).thenReturn(passwordUser);
        when(passwordEncoder.matches("secret", "$2a$12$hashedPassword")).thenReturn(true);
        when(jobService.countRunningJobs(passwordUserId)).thenReturn(0L);
        when(executor.countRowsForUser(passwordUserId)).thenReturn(Map.of());

        GmailConnection conn = new GmailConnection();
        conn.setEncryptedRefreshToken("token-value");
        when(gmailConnectionRepository.findByUserId(passwordUserId)).thenReturn(List.of(conn));

        accountDeletionService.deleteCurrentUser(new DeleteAccountRequest("secret", null), httpRequest);

        InOrder order = inOrder(gmailConnectionRepository, executor);
        order.verify(gmailConnectionRepository).findByUserId(passwordUserId);
        order.verify(executor).deleteUserAndVerify(passwordUserId);
    }

    @Test
    void getDeletionSummary_totalsTheCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("accounts", 3L);
        counts.put("transactions", 150L);
        when(executor.countRowsForUser(passwordUserId)).thenReturn(counts);

        DeletionSummaryResponse summary = accountDeletionService.getDeletionSummary(passwordUserId);

        assertEquals(153L, summary.total());
        assertEquals(3L, summary.counts().get("accounts"));
        assertEquals(150L, summary.counts().get("transactions"));
    }
}
