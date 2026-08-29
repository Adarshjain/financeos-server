package com.financeos.domain.user;

import com.financeos.api.auth.dto.DeleteAccountRequest;
import com.financeos.api.auth.dto.DeletionSummaryResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.AccountDeletionLimiter;
import com.financeos.domain.job.JobService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** Codes the client switches on. Keep in sync with the client's DeleteAccountCard. */
    public static final String CODE_FORBIDDEN = "ACCOUNT_DELETE_FORBIDDEN";
    public static final String CODE_BUSY = "ACCOUNT_DELETE_BUSY";

    private static final long JOB_DRAIN_TIMEOUT_MS = 5000;
    private static final long JOB_DRAIN_POLL_MS = 250;

    private final AuthService authService;
    private final AccountDeletionLimiter accountDeletionLimiter;
    private final AccountDeletionExecutor executor;
    private final JobService jobService;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AccountDeletionService(
            AuthService authService,
            AccountDeletionLimiter accountDeletionLimiter,
            AccountDeletionExecutor executor,
            JobService jobService,
            GmailConnectionRepository gmailConnectionRepository,
            PasswordEncoder passwordEncoder,
            @Autowired(required = false) FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.authService = authService;
        this.accountDeletionLimiter = accountDeletionLimiter;
        this.executor = executor;
        this.jobService = jobService;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRepository = sessionRepository;
    }

    public DeletionSummaryResponse getDeletionSummary(UUID userId) {
        Map<String, Long> counts = executor.countRowsForUser(userId);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new DeletionSummaryResponse(counts, total);
    }

    public void deleteCurrentUser(DeleteAccountRequest request, HttpServletRequest httpRequest) {
        User user = authService.getCurrentUser();
        UUID userId = user.getId();
        String email = user.getEmail();

        // 1. Throttle repeated confirmation failures (a stolen session must not be able
        //    to brute-force its way to a deletion).
        accountDeletionLimiter.assertNotLockedOut(userId);

        // 2. Re-authenticate. Google-only accounts have no password_hash, so a password
        //    check alone would lock them out of deleting their own account.
        boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
        if (!isConfirmed(request, user, hasPassword)) {
            accountDeletionLimiter.recordFailure(userId);
            log.warn("Account deletion rejected due to invalid credentials: userId={}", userId,
                    StructuredArguments.keyValue("event", Events.ACCOUNT_DELETE_REJECTED),
                    StructuredArguments.keyValue("userId", userId),
                    StructuredArguments.keyValue("hasPassword", hasPassword));
            throw new ApiStatusException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN,
                    hasPassword
                            ? "That password is not correct."
                            : "That email address does not match this account.");
        }
        accountDeletionLimiter.reset(userId);

        // 3. Quiesce in-flight jobs. A Gmail sync or ingestion job committing rows
        //    mid-delete would race the cascade, so cancel and wait for them to finish.
        jobService.requestCancelAllUserJobs(userId);
        if (!waitForJobsToDrain(userId)) {
            throw new ApiStatusException(HttpStatus.CONFLICT, CODE_BUSY,
                    "A background task is still finishing. Try again in a few seconds.");
        }

        // 4. Snapshot the counts before they are gone, for the audit log line.
        DeletionSummaryResponse summary = getDeletionSummary(userId);

        // 5. Hand back the Google grants while the tokens are still readable. Best
        //    effort: Google being unreachable must not strand the deletion.
        revokeGoogleGrants(userId);

        // 6. Delete and verify, atomically, in its own bean so the transaction applies.
        executor.deleteUserAndVerify(userId);

        // 7. Only now, after the row is gone for good, drop the sessions.
        destroySessions(email, httpRequest);

        log.info("User account successfully deleted: userId={}, totalRowsDeleted={}", userId, summary.total(),
                StructuredArguments.keyValue("event", Events.ACCOUNT_DELETED),
                StructuredArguments.keyValue("userId", userId),
                StructuredArguments.keyValue("deletedCounts", summary.counts()),
                StructuredArguments.keyValue("totalRowsDeleted", summary.total()));
    }

    private boolean isConfirmed(DeleteAccountRequest request, User user, boolean hasPassword) {
        if (request == null) {
            return false;
        }
        if (hasPassword) {
            String password = request.password();
            return password != null && !password.isBlank()
                    && passwordEncoder.matches(password, user.getPasswordHash());
        }
        String confirmEmail = request.confirmEmail();
        String email = user.getEmail();
        return confirmEmail != null && !confirmEmail.isBlank() && email != null
                && confirmEmail.trim().equalsIgnoreCase(email.trim());
    }

    private boolean waitForJobsToDrain(UUID userId) {
        long deadline = System.currentTimeMillis() + JOB_DRAIN_TIMEOUT_MS;
        while (true) {
            if (jobService.countRunningJobs(userId) == 0) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(JOB_DRAIN_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void revokeGoogleGrants(UUID userId) {
        try {
            List<GmailConnection> connections = gmailConnectionRepository.findByUserId(userId);
            for (GmailConnection connection : connections) {
                // The converter decrypts on read, so this getter yields the real token.
                String token = connection.getEncryptedRefreshToken();
                if (token != null && !token.isBlank()) {
                    revokeGoogleToken(token);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load Gmail connections for revoke: {}", e.getMessage(),
                    StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_REVOKE_FAILED));
        }
    }

    private void revokeGoogleToken(String token) {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/revoke"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OAuth Google revoke returned status {}", response.statusCode(),
                        StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_REVOKE_FAILED),
                        StructuredArguments.keyValue("status", response.statusCode()));
            }
        } catch (Exception e) {
            log.warn("OAuth Google revoke failed: {}", e.getMessage(),
                    StructuredArguments.keyValue("event", Events.OAUTH_GOOGLE_REVOKE_FAILED));
        }
    }

    private void destroySessions(String email, HttpServletRequest httpRequest) {
        if (sessionRepository != null && email != null && !email.isBlank()) {
            try {
                // Every session, not just this request's — another device would otherwise
                // keep a live cookie for an account that no longer exists.
                Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(email);
                if (sessions != null) {
                    for (String sessionId : sessions.keySet()) {
                        sessionRepository.deleteById(sessionId);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to delete Spring sessions for principal: {}", e.getMessage());
            }
        }

        if (httpRequest != null) {
            try {
                HttpSession session = httpRequest.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } catch (Exception e) {
                log.warn("Failed to invalidate current HTTP session: {}", e.getMessage());
            }
        }

        SecurityContextHolder.clearContext();
    }
}
