package com.financeos.gmail.ingest;

import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.gmail.attention.NumberGroupKey;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /**
     * {@link #settleAll} in its own transaction, for callers that run after another transaction committed (where a plain
     * {@code @Transactional} would join the finished one and never commit). The boundary is this method's, so a caller's
     * try/catch around it also catches a failed commit.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int settleAllInNewTransaction(UUID userId) {
        return settleAll(userId);
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
        return settle(userId, last4, null);
    }

    /**
     * True when the parked row may be attached to the account: it is in the given (bank, last4) group (any group when
     * {@code groupBank} is null) and its bank is not known to differ from the account's.
     */
    public static boolean belongsTo(GmailProcessedMessage row, String groupBank, Account account) {
        String rowBank = NumberGroupKey.bankOf(row.getClassifiedBank(), row.getSenderAddress());
        return (groupBank == null || NumberGroupKey.sameBank(groupBank, rowBank))
                && NumberGroupKey.attachable(rowBank, account.getBank());
    }

    /** As {@link #settle(UUID, String)}, limited to one bank's group when {@code groupBank} is set. */
    @Transactional
    public int settle(UUID userId, String last4, String groupBank) {
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
        List<GmailProcessedMessage> rows = new ArrayList<>(ingestFromDate == null
                ? processedMessageRepository.findParked(userId, last4, List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT))
                : processedMessageRepository.findParkedBefore(userId, last4, PARKED, windowStart(ingestFromDate)));
        rows.removeIf(row -> !belongsTo(row, groupBank, account)); // another bank's number stays parked
        if (rows.isEmpty()) {
            return 0;
        }

        for (GmailProcessedMessage row : rows) {
            row.setAccount(account);
            if (ingestFromDate == null && account.isClosed()) {
                row.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK); // closed with import off: nothing to turn on
                row.setError(null);
            } else if (ingestFromDate == null) {
                Instant archivedAt = row.getArchivedAt();
                row.setStatus(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
                // same email, still parked: the user's archive stands; else it shows as one entry per account, not a row
                row.setArchivedAt(archivedAt != null ? archivedAt : Instant.now());
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
