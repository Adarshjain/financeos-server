package com.financeos.gmail.ingest;

import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Settles parked ledger rows (unresolved account, not opted in) whose outcome is already known
 * once their last 4 digits resolve to one account, without fetching the email again:
 * <ul>
 *   <li>account has an ingest date: email older than the window that date reopens is skipped as
 *       before the watermark (moving the date earlier reactivates it);</li>
 *   <li>account has no ingest date: an unresolved email is re-parked as not opted in, against
 *       that account.</li>
 * </ul>
 * Reprocessing would reach the same result at the cost of a Gmail fetch and an LLM call per
 * email. Email inside the window is left to reactivation, which reprocesses it.
 */
@Component
public class ParkedMessageSettler {

    private static final Logger log = LoggerFactory.getLogger(ParkedMessageSettler.class);

    private static final List<GmailProcessedStatus> PARKED = List.of(
            GmailProcessedStatus.UNRESOLVED_ACCOUNT,
            GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);

    private final GmailProcessedMessageRepository processedMessageRepository;
    private final AccountResolver accountResolver;
    private final GmailIngestProperties ingestProperties;

    public ParkedMessageSettler(GmailProcessedMessageRepository processedMessageRepository,
                                AccountResolver accountResolver,
                                GmailIngestProperties ingestProperties) {
        this.processedMessageRepository = processedMessageRepository;
        this.accountResolver = accountResolver;
        this.ingestProperties = ingestProperties;
    }

    /** First instant of the window an ingest date reopens: email from here on is worth reprocessing. */
    public Instant windowStart(LocalDate ingestFromDate) {
        return ingestFromDate.minusDays(ingestProperties.getDateWindowDays())
                .atStartOfDay(AppTime.zone())
                .toInstant();
    }

    /** Settles every parked last 4 of the user; the Gmail sync runs it so rows parked before their account existed heal. */
    @Transactional
    public int settleAll(UUID userId) {
        int settled = 0;
        for (String last4 : processedMessageRepository.findParkedLast4s(userId, PARKED)) {
            settled += settle(userId, last4);
        }
        return settled;
    }

    @Transactional
    public int settle(UUID userId, String last4) {
        if (last4 == null || last4.isBlank()) {
            return 0;
        }
        Account account = UserContextHelper.callAs(userId, () -> accountResolver.resolve(last4))
                .map(AccountResolver.ResolvedCard::account)
                .orElse(null);
        if (account == null) {
            return 0; // still no single account: the parked reason stands
        }

        LocalDate ingestFromDate = account.getIngestFromDate();
        List<GmailProcessedMessage> rows = ingestFromDate == null
                ? processedMessageRepository.findParked(userId, last4, List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT))
                : processedMessageRepository.findParkedBefore(userId, last4, PARKED, windowStart(ingestFromDate));
        if (rows.isEmpty()) {
            return 0;
        }

        for (GmailProcessedMessage row : rows) {
            row.setAccount(account);
            if (ingestFromDate == null) {
                Instant archivedAt = row.getArchivedAt();
                row.setStatus(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
                row.setArchivedAt(archivedAt); // same email, still parked: the user's archive stands
                row.setError("Account " + account.getName() + " is not opted in for Gmail ingestion");
            } else {
                row.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
                row.setError(null);
            }
            row.setAttemptCount(0);
            row.setNextRetryAt(null);
        }
        processedMessageRepository.saveAll(rows);
        log.info("Settled {} parked rows for last4 {} against account {} ({})", rows.size(), last4, account.getId(),
                ingestFromDate == null ? "not opted in" : "before " + ingestFromDate);
        return rows.size();
    }
}
