package com.financeos.domain.nps.gmail;

import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Queues the import of the Gmail statements held for a PRAN once an account for it exists (the user's go-ahead, or
 * the user adding the account themselves). The import itself runs outside the request, as an
 * {@link JobType#NPS_GMAIL_IMPORT} job (see {@link NpsGmailHeldImporter}); the job is enqueued inside the caller's
 * transaction, so it only starts after the account is committed.
 */
@Component
public class NpsGmailHeldQueue {

    /** The job's payload. */
    public record Payload(UUID accountId) {
    }

    private final NpsGmailPendingRepository pendingRepository;
    private final NpsAccountRepository accountRepository;
    private final JobService jobService;

    public NpsGmailHeldQueue(NpsGmailPendingRepository pendingRepository, NpsAccountRepository accountRepository,
                             JobService jobService) {
        this.pendingRepository = pendingRepository;
        this.accountRepository = accountRepository;
        this.jobService = jobService;
    }

    /**
     * Un-ignores the held rows of the account's PRAN (creating the account is the user changing their mind), gives
     * them a fresh set of attempts and enqueues the import when any are waiting. Returns how many statements will be
     * imported.
     */
    public int queueImport(UUID userId, NpsAccount account) {
        List<NpsGmailPending> held = pendingRepository.findHeld(userId,
                List.of(NpsGmailPendingStatus.PENDING, NpsGmailPendingStatus.IGNORED));
        List<NpsGmailPending> mine = new ArrayList<>();
        for (NpsGmailPending p : held) {
            if (account.getPran().equals(p.getPran())) {
                p.setStatus(NpsGmailPendingStatus.PENDING);
                p.setAttempts(0);
                mine.add(p);
            }
        }
        if (mine.isEmpty()) {
            return 0;
        }
        pendingRepository.saveAll(mine);
        enqueue(userId, account);
        return mine.size();
    }

    /**
     * Every Gmail sync: queues the import again for held PENDING rows of PRANs the user owns (an account reading
     * Gmail) that have fewer than {@value NpsGmailPending#MAX_ATTEMPTS} attempts. Two queries, one job per account.
     * Returns how many statements were queued.
     */
    public int requeueStranded(UUID userId) {
        return queueOwned(userId, false);
    }

    /**
     * The user's retry of statements that failed {@value NpsGmailPending#MAX_ATTEMPTS} times: resets their attempts and
     * queues the import. Returns how many statements are being imported.
     */
    public int retryFailed(UUID userId) {
        return queueOwned(userId, true);
    }

    private int queueOwned(UUID userId, boolean retry) {
        List<NpsGmailPending> held = pendingRepository.findHeld(userId, List.of(NpsGmailPendingStatus.PENDING));
        if (held.isEmpty()) {
            return 0;
        }
        Map<String, NpsAccount> byPran = new HashMap<>();
        for (NpsAccount a : accountRepository.findAllByUser_Id(userId)) {
            if (retry || a.isReadGmail()) {
                byPran.put(a.getPran(), a);
            }
        }
        Map<UUID, NpsAccount> accounts = new LinkedHashMap<>();
        List<NpsGmailPending> rows = new ArrayList<>();
        for (NpsGmailPending p : held) {
            NpsAccount account = p.getPran() == null ? null : byPran.get(p.getPran());
            boolean failed = p.getAttempts() >= NpsGmailPending.MAX_ATTEMPTS;
            if (account == null || failed != retry) {
                continue;
            }
            if (retry) {
                p.setAttempts(0);
            }
            rows.add(p);
            accounts.put(account.getId(), account);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        if (retry) {
            pendingRepository.saveAll(rows);
        }
        accounts.values().forEach(a -> enqueue(userId, a));
        return rows.size();
    }

    /** A pending or running job of the account covers the new request: the running job re-reads the queue between passes. */
    private void enqueue(UUID userId, NpsAccount account) {
        jobService.enqueue(userId, JobType.NPS_GMAIL_IMPORT, JobTrigger.USER, new Payload(account.getId()), null,
                "nps-gmail-import:" + account.getId());
    }
}
