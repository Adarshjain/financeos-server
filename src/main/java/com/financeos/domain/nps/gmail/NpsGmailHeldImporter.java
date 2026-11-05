package com.financeos.domain.nps.gmail;

import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.internal.MessageGoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports every held Gmail statement of an account's PRAN (the work of the {@code NPS_GMAIL_IMPORT} job): each PDF is
 * fetched from Gmail again and applied with source GMAIL in period order, each in its own transaction, so one failure
 * leaves the others imported. A failed statement stays PENDING with the reason and one more attempt counted (the next Gmail sync queues it again
 * until {@value NpsGmailPending#MAX_ATTEMPTS} attempts, then it waits for the user's retry). Never logs the PRAN or the holder.
 */
@Component
public class NpsGmailHeldImporter {

    private static final Logger log = LoggerFactory.getLogger(NpsGmailHeldImporter.class);

    /** What a run did. */
    public record Result(int imported, int failed) {
    }

    private final NpsAccountRepository accountRepository;
    private final NpsGmailPendingRepository pendingRepository;
    private final NpsGmailStatementService statementService;
    private final NpsGmailPdfFetcher fetcher;

    public NpsGmailHeldImporter(NpsAccountRepository accountRepository, NpsGmailPendingRepository pendingRepository,
                                NpsGmailStatementService statementService, NpsGmailPdfFetcher fetcher) {
        this.accountRepository = accountRepository;
        this.pendingRepository = pendingRepository;
        this.statementService = statementService;
        this.fetcher = fetcher;
    }

    /** Most passes one run makes over the account's held rows (rows queued mid-run are picked up by the next pass). */
    static final int MAX_PASSES = 5;

    /**
     * Runs passes over the account's eligible PENDING rows until none are left that this run has not tried (at most
     * {@value #MAX_PASSES}); every row is tried at most once per run, so an attempt is never counted twice.
     */
    public Result run(UUID userId, UUID accountId) {
        NpsAccount account = accountRepository.findByIdAndUser_Id(accountId, userId).orElse(null);
        if (account == null) {
            return new Result(0, 0);
        }
        Set<UUID> tried = new HashSet<>();
        int imported = 0;
        int failed = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<NpsGmailPending> rows = eligible(userId, account).stream().filter(p -> !tried.contains(p.getId())).toList();
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(p -> tried.add(p.getId()));
            Result r = runPass(userId, rows);
            imported += r.imported();
            failed += r.failed();
        }
        return new Result(imported, failed);
    }

    private List<NpsGmailPending> eligible(UUID userId, NpsAccount account) {
        return pendingRepository.findHeld(userId, List.of(NpsGmailPendingStatus.PENDING)).stream()
                .filter(p -> account.getPran().equals(p.getPran()) && p.getAttempts() < NpsGmailPending.MAX_ATTEMPTS).toList();
    }

    private Result runPass(UUID userId, List<NpsGmailPending> rows) {
        Map<UUID, GmailConnection> connections = fetcher.connectionsOf(userId, rows);
        int imported = 0;
        int failed = 0;
        for (NpsGmailPending row : rows) {
            String problem;
            try {
                GmailConnection connection = row.getConnectionId() == null ? null : connections.get(row.getConnectionId());
                if (connection == null) {
                    problem = "The Gmail account this statement came from is not connected any more.";
                } else {
                    NpsGmailStatementService.Outcome outcome = statementService.importHeld(userId, row,
                            fetcher.fetch(connection, row.getGmailMessageId()));
                    problem = outcome.kind() == NpsGmailStatementService.Kind.IMPORTED ? null : outcome.why();
                }
            } catch (MessageGoneException e) {
                problem = "The email is no longer in your mailbox.";
            } catch (Exception e) {
                log.warn("Held NPS statement import failed: {}", e.getClass().getSimpleName());
                problem = "The import failed (" + e.getClass().getSimpleName() + ").";
            }
            if (problem == null) {
                imported++;
            } else {
                failed++;
                NpsGmailPending fresh = pendingRepository.findByUserIdAndMessageId(userId, row.getGmailMessageId())
                        .orElse(null);
                if (fresh != null && fresh.getStatus() == NpsGmailPendingStatus.PENDING) {
                    fresh.setAttempts(fresh.getAttempts() + 1);
                    fresh.setLastError(problem.length() > 300 ? problem.substring(0, 300) : problem);
                    pendingRepository.save(fresh);
                }
            }
        }
        return new Result(imported, failed);
    }
}
