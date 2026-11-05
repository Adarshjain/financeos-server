package com.financeos.domain.nps.gmail;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import com.financeos.domain.nps.NpsService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.internal.MessageGoneException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The user's answers to what Gmail found: the go-ahead for a detected PRAN (creates the account, the held statements
 * import in the background), "ignore", and the password of locked statements. Every lookup names the user; another
 * user's detection key is a 404 (the key depends on the user).
 */
@Service
public class NpsGmailDetectionService {

    /** The go-ahead's answer: the new account and how many held statements are being imported. */
    public record Accepted(UUID accountId, int importing) {
    }

    /** Most locked statements one password attempt tries. */
    static final int UNLOCK_BATCH = 20;

    /** The password attempt's answer. */
    public record Unlocked(int opened, int stillLocked) {
    }

    private final NpsGmailDetections detections;
    private final NpsGmailPendingRepository pendingRepository;
    private final NpsService npsService;
    private final NpsGmailStatementService statementService;
    private final NpsGmailPdfFetcher fetcher;
    private final NpsGmailHeldQueue heldQueue;

    public NpsGmailDetectionService(NpsGmailDetections detections, NpsGmailPendingRepository pendingRepository,
                                    NpsService npsService, NpsGmailStatementService statementService,
                                    NpsGmailPdfFetcher fetcher, NpsGmailHeldQueue heldQueue) {
        this.detections = detections;
        this.pendingRepository = pendingRepository;
        this.npsService = npsService;
        this.statementService = statementService;
        this.fetcher = fetcher;
        this.heldQueue = heldQueue;
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        return userId;
    }

    private NpsGmailDetections.Group owned(UUID userId, UUID key) {
        NpsGmailDetections.Group group = detections.find(userId, key);
        if (group == null) {
            throw new ResourceNotFoundException("NPS Gmail detection", key);
        }
        return group;
    }

    /**
     * The go-ahead: creates the account (sector = the request's, else All Citizen when the POP is eNPS, else none)
     * and queues the import of every held statement of that PRAN.
     */
    public Accepted addAccount(UUID key, NpsRelation relation, NpsSector sector) {
        UUID userId = currentUserId();
        NpsGmailDetections.Group group = owned(userId, key);
        if (relation == null) {
            throw new ValidationException("Say whether the account is yours or your child's.");
        }
        NpsService.Created created = npsService.createDetectedAccount(group.pran(), group.detection().holderName(),
                relation, sector != null ? sector : group.detection().sectorGuess());
        return new Accepted(created.accountId(), created.importing());
    }

    /** Ignores the PRAN: all its held statements become IGNORED, and later mails of it are ignored too. */
    public void ignore(UUID key) {
        UUID userId = currentUserId();
        NpsGmailDetections.Group group = owned(userId, key);
        group.rows().forEach(p -> p.setStatus(NpsGmailPendingStatus.IGNORED));
        pendingRepository.saveAll(group.rows());
    }

    /**
     * Tries the password on every password-locked statement of the user. The ones it opens become detections (or
     * import straight away when their PRAN is an account reading Gmail); the rest stay locked.
     */
    public Unlocked unlock(String password) {
        UUID userId = currentUserId();
        if (password == null || password.isBlank()) {
            throw new ValidationException("Enter the statement password.");
        }
        List<NpsGmailPending> all = pendingRepository.findLockedForUnlock(userId);
        // one call tries at most UNLOCK_BATCH statements (each is a Gmail fetch and a parse); the rest wait for another tap
        List<NpsGmailPending> locked = all.size() > UNLOCK_BATCH ? all.subList(0, UNLOCK_BATCH) : all;
        Instant now = Instant.now();
        locked.forEach(p -> p.setLastTriedAt(now));
        pendingRepository.saveAll(locked);
        Map<UUID, GmailConnection> connections = fetcher.connectionsOf(userId, locked);
        int opened = 0;
        int stillLocked = all.size() - locked.size();
        for (NpsGmailPending row : locked) {
            GmailConnection connection = row.getConnectionId() == null ? null : connections.get(row.getConnectionId());
            if (connection == null) {
                stillLocked++;
                continue;
            }
            try {
                byte[] pdf;
                try {
                    pdf = fetcher.fetch(connection, row.getGmailMessageId());
                } catch (MessageGoneException e) {
                    markIgnored(row.getId(), "The email is no longer in your mailbox.");
                    continue;
                }
                NpsGmailStatementService.Outcome outcome = statementService.process(userId, row.getConnectionId(),
                        row.getGmailMessageId(), pdf, password);
                switch (outcome.kind()) {
                    case PASSWORD_NEEDED -> stillLocked++;
                    case IGNORED -> {
                        if (NpsGmailStatementService.READING_OFF.equals(outcome.why())) {
                            // not the statement's fault: it stays locked-and-waiting until reading is turned on
                            setError(row.getId(), outcome.why());
                            stillLocked++;
                        } else {
                            markIgnored(row.getId(), outcome.why());
                        }
                    }
                    default -> opened++;
                }
            } catch (RuntimeException e) {
                // one bad statement must not lose the answer for the others
                stillLocked++;
                setError(row.getId(), "The statement could not be read (" + e.getClass().getSimpleName() + ").");
            }
        }
        return new Unlocked(opened, stillLocked);
    }

    /** The user's retry of held statements of their accounts that failed to import 3 times; how many are importing. */
    public int retryFailed() {
        return heldQueue.retryFailed(currentUserId());
    }

    private void setError(UUID rowId, String why) {
        pendingRepository.findById(rowId).ifPresent(p -> {
            if (p.getStatus() == NpsGmailPendingStatus.PENDING) {
                p.setLastError(why == null ? null : why.length() > 300 ? why.substring(0, 300) : why);
                pendingRepository.save(p);
            }
        });
    }

    private void markIgnored(UUID rowId, String why) {
        pendingRepository.findById(rowId).ifPresent(p -> {
            p.setStatus(NpsGmailPendingStatus.IGNORED);
            p.setLastError(why == null ? null : why.length() > 300 ? why.substring(0, 300) : why);
            pendingRepository.save(p);
        });
    }
}
