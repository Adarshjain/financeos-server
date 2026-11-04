package com.financeos.domain.statement;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.observability.AuditLogger;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.notification.feed.AppNotificationRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.StatementDuplicateReconciler;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.transaction.link.TransactionLinkService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves a statement and the transactions its lines point at to another account of the same type, in place: edits
 * (category, notes, splits, tags), links and loan/lending references stay because the rows are the same rows.
 * Rows another statement also has a line for stay where they are, as on delete. Everything runs in one transaction;
 * the duplicate pairing and auto-linking the normal import does afterwards runs for the target (merges and the
 * auto-link event fire after commit).
 */
@Service
public class StatementMoveService {

    private static final int IN_CHUNK = 500;

    /** What the move did: rows moved, and rows newly flagged as possible duplicates on the target. */
    public record Moved(int moved, int duplicatesFlagged) {}

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkRepository transactionLinkRepository;
    private final TransactionLinkService transactionLinkService;
    private final TransactionMatcher transactionMatcher;
    private final ReviewStatusManager reviewStatusManager;
    private final StatementDuplicateReconciler duplicateReconciler;
    private final CardRepository cardRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final GmailProcessedMessageRepository gmailMessageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogger auditLogger;

    public StatementMoveService(AccountRepository accountRepository,
                                StatementRepository statementRepository,
                                StatementTransactionRepository statementTransactionRepository,
                                TransactionRepository transactionRepository,
                                TransactionLinkRepository transactionLinkRepository,
                                TransactionLinkService transactionLinkService,
                                TransactionMatcher transactionMatcher,
                                ReviewStatusManager reviewStatusManager,
                                StatementDuplicateReconciler duplicateReconciler,
                                CardRepository cardRepository,
                                AppNotificationRepository appNotificationRepository,
                                GmailProcessedMessageRepository gmailMessageRepository,
                                ApplicationEventPublisher eventPublisher,
                                AuditLogger auditLogger) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
        this.transactionLinkService = transactionLinkService;
        this.transactionMatcher = transactionMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.duplicateReconciler = duplicateReconciler;
        this.cardRepository = cardRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.gmailMessageRepository = gmailMessageRepository;
        this.eventPublisher = eventPublisher;
        this.auditLogger = auditLogger;
    }

    /** The caller has validated ownership, account types and that the target has no such statement. */
    @Transactional
    public Moved move(UUID userId, UUID sourceId, UUID targetId, UUID statementId) {
        Account source = accountRepository.findByIdAndUser_Id(sourceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", sourceId));
        Account target = accountRepository.findByIdAndUser_Id(targetId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", targetId));
        Statement statement = statementRepository.findByIdAndAccount_Id(statementId, sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));
        List<Transaction> moving = rowsToMove(userId, source.getId(), statement.getId());
        Set<UUID> movingIds = new HashSet<>();
        for (Transaction t : moving) {
            movingIds.add(t.getId());
        }

        // Possible-duplicate flags between a moved row and a row staying in the source stop applying; decided before the rows move.
        List<Transaction> sourcePartners = sourcePartnersToRecheck(source.getId(), moving, movingIds);
        clearMovedFlagsWithoutLookAlike(moving);

        Map<String, Card> targetCards = new HashMap<>();
        for (Card c : cardRepository.findOpenByAccountId(target.getId())) {
            targetCards.putIfAbsent(c.getLast4(), c);
        }
        for (Transaction t : moving) {
            t.setAccount(target);
            Card card = t.getCard();
            if (card != null && !card.getAccount().getId().equals(target.getId())) {
                t.setCard(targetCards.get(card.getLast4()));
            }
        }
        transactionRepository.saveAll(moving);
        statement.setAccount(target);
        statementRepository.save(statement);
        appNotificationRepository.moveStatementRows(userId, statement.getId(), target.getId());
        moveGmailLedger(userId, source.getId(), target, statement, moving);

        unlinkLinksThatNeedTwoAccounts(movingIds, target.getId());
        clearSourceFlagsWithoutLookAlike(source.getId(), sourcePartners, movingIds);

        source.setLastStatementDate(statementRepository.findLatestPeriodEnd(source.getId()));
        accountRepository.save(source);
        // the same effective end the import uses: the period end, else the latest line date
        LocalDate effectiveEnd = statement.getPeriodEnd() != null ? statement.getPeriodEnd() : maxDate(moving);
        if (effectiveEnd != null
                && (target.getLastStatementDate() == null || effectiveEnd.isAfter(target.getLastStatementDate()))) {
            target.setLastStatementDate(effectiveEnd);
            accountRepository.save(target);
        }

        int flagged = pairWithTargetRows(target.getId(), statement, moving);
        if (!moving.isEmpty()) {
            eventPublisher.publishEvent(new TransactionsIngestedEvent(userId)); // auto-linking runs after commit
        }
        auditLogger.mutation("Statement", statement.getId(), "MOVE", "user:" + UserContext.getCurrentUserId(),
                "manual", List.of("account"), moving.size(), null, "INR");
        return new Moved(moving.size(), flagged);
    }

    /** Rows on the source account that this statement has a line for and no other statement does. */
    private List<Transaction> rowsToMove(UUID userId, UUID sourceId, UUID statementId) {
        List<UUID> linkedIds = statementTransactionRepository.findTransactionIdsByStatementId(statementId);
        List<Transaction> moving = new ArrayList<>();
        for (List<UUID> chunk : chunks(linkedIds)) {
            Set<UUID> elsewhere = statementTransactionRepository.findLinkedToOtherStatements(chunk, statementId);
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                if (t.getAccount() != null && sourceId.equals(t.getAccount().getId()) && !elsewhere.contains(t.getId())) {
                    moving.add(t);
                }
            }
        }
        return moving;
    }

    /** Flagged rows staying in the source that look like a moved row. */
    private List<Transaction> sourcePartnersToRecheck(UUID sourceId, List<Transaction> moving, Set<UUID> movingIds) {
        List<Transaction> partners = new ArrayList<>();
        for (Transaction survivor : window(sourceId, moving, StatementDuplicateReconciler.WINDOW_DAYS)) {
            if (movingIds.contains(survivor.getId()) || !survivor.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT)) {
                continue;
            }
            if (moving.stream().anyMatch(m -> looksLike(m, survivor))) {
                partners.add(survivor);
            }
        }
        return partners;
    }

    /** A moved row flagged only against rows that stay behind is no longer a duplicate of anything. */
    private void clearMovedFlagsWithoutLookAlike(List<Transaction> moving) {
        for (Transaction m : moving) {
            if (m.getReviewReasons() == null || !m.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT)) {
                continue;
            }
            boolean lookAlikeMoves = moving.stream().anyMatch(o -> o != m && looksLike(m, o));
            if (!lookAlikeMoves) {
                reviewStatusManager.clearReason(m, ReviewReason.DUPLICATE_SUSPECT, ReviewType.AUTO_REVIEWED);
            }
        }
    }

    /**
     * The two rules that flag a duplicate: same-day look-alikes, and a statement row with an alert/manual row of the
     * same direction and amount within the reconciler's window.
     */
    private boolean looksLike(Transaction a, Transaction b) {
        if (transactionMatcher.areDuplicates(a, b, 0)) {
            return true;
        }
        if (StatementDuplicateReconciler.isAlertOrManual(a) == StatementDuplicateReconciler.isAlertOrManual(b)
                || a.getDate() == null || b.getDate() == null || a.getAmount() == null || b.getAmount() == null
                || a.getType() != b.getType() || a.getAmount().compareTo(b.getAmount()) != 0) {
            return false;
        }
        boolean sameCard = a.getCard() == null || b.getCard() == null || a.getCard().getId().equals(b.getCard().getId());
        return sameCard && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(a.getDate(), b.getDate())) <= StatementDuplicateReconciler.WINDOW_DAYS;
    }

    /** The mail the statement came from and the alert mails of moved rows now belong to the target's ledger. */
    private void moveGmailLedger(UUID userId, UUID sourceId, Account target, Statement statement, List<Transaction> moving) {
        Set<String> messageIds = new HashSet<>();
        if (statement.getSource() == StatementSource.gmail && statement.getSourceRef() != null) {
            messageIds.add(statement.getSourceRef());
        }
        for (Transaction t : moving) {
            if (t.getSource() == TransactionSource.gmail_transaction_alert && t.getSourceMessageId() != null) {
                messageIds.add(t.getSourceMessageId());
            }
        }
        if (messageIds.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(messageIds);
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            gmailMessageRepository.moveToAccount(userId, sourceId, target, ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
    }

    private void clearSourceFlagsWithoutLookAlike(UUID sourceId, List<Transaction> partners, Set<UUID> movedIds) {
        if (partners.isEmpty()) {
            return;
        }
        List<Transaction> remaining = window(sourceId, partners, StatementDuplicateReconciler.WINDOW_DAYS).stream().filter(t -> !movedIds.contains(t.getId())).toList();
        List<Transaction> cleared = new ArrayList<>();
        for (Transaction partner : partners) {
            boolean stillHasLookAlike = remaining.stream()
                    .anyMatch(r -> !r.getId().equals(partner.getId()) && looksLike(r, partner));
            if (!stillHasLookAlike) {
                reviewStatusManager.clearReason(partner, ReviewReason.DUPLICATE_SUSPECT, ReviewType.AUTO_REVIEWED);
                cleared.add(partner);
            }
        }
        transactionRepository.saveAll(cleared);
    }

    /**
     * Links stay (the rows keep their ids). A transfer or card-payment link needs two accounts, so one whose
     * members now all sit in the target is removed, and a reversal needs one account, so one now split is removed the way the unlink action removes it.
     */
    private void unlinkLinksThatNeedTwoAccounts(Set<UUID> movedIds, UUID targetId) {
        Set<UUID> toDelete = new HashSet<>();
        for (List<UUID> chunk : chunks(new ArrayList<>(movedIds))) {
            for (TransactionLink link : transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(chunk)) {
                if (link.getType() != LinkType.TRANSFER && link.getType() != LinkType.CC_PAYMENT && link.getType() != LinkType.REVERSAL) {
                    continue;
                }
                boolean allInTarget = link.getMembers().stream().map(TransactionLinkMember::getTransaction)
                        .allMatch(t -> t.getAccount() != null && targetId.equals(t.getAccount().getId()));
                if (link.getType() == LinkType.REVERSAL) {
                    // a reversal needs one account: now split across accounts
                    boolean split = link.getMembers().stream().map(m -> m.getTransaction().getAccount().getId()).distinct().count() > 1;
                    if (split) {
                        toDelete.add(link.getId());
                    }
                    continue;
                }
                if (allInTarget) {
                    toDelete.add(link.getId());
                }
            }
        }
        for (UUID linkId : toDelete) {
            transactionLinkService.deleteLink(linkId);
        }
    }

    /**
     * What the import does for new rows: flag look-alikes among the target's statement rows, then pair the moved
     * rows with the target's alert/manual rows (certain pairs merge after commit, the rest are flagged).
     */
    private int pairWithTargetRows(UUID targetId, Statement statement, List<Transaction> moving) {
        if (moving.isEmpty()) {
            return 0;
        }
        List<Transaction> existing = window(targetId, moving, StatementDuplicateReconciler.WINDOW_DAYS);
        Set<UUID> movedIds = new HashSet<>();
        for (Transaction m : moving) {
            movedIds.add(m.getId());
        }
        Set<UUID> flagged = new HashSet<>();
        List<Transaction> touched = new ArrayList<>();
        for (Transaction row : moving) {
            for (Transaction db : existing) {
                if (movedIds.contains(db.getId()) || StatementDuplicateReconciler.isAlertOrManual(db)) {
                    continue;
                }
                if (transactionMatcher.areDuplicates(row, db, 0)) {
                    flagged.add(row.getId());
                    reviewStatusManager.addReason(row, ReviewReason.DUPLICATE_SUSPECT);
                    reviewStatusManager.addReason(db, ReviewReason.DUPLICATE_SUSPECT);
                    touched.add(db);
                }
            }
        }

        LocalDate start = statement.getPeriodStart() != null ? statement.getPeriodStart() : minDate(moving);
        LocalDate end = statement.getPeriodEnd() != null ? statement.getPeriodEnd() : maxDate(moving);
        Set<UUID> pairedIds = new HashSet<>(movedIds);
        Set<UUID> claimed = statementTransactionRepository.findLinkedTransactionIds(targetId, minDate(moving).minusDays(StatementDuplicateReconciler.WINDOW_DAYS),
                maxDate(moving).plusDays(StatementDuplicateReconciler.WINDOW_DAYS));
        if (claimed != null) {
            pairedIds.addAll(claimed);
        }
        List<Transaction> statementRows = moving.stream().filter(t -> !StatementDuplicateReconciler.isAlertOrManual(t)).toList();
        List<StatementDuplicateReconciler.Pair> certain = new ArrayList<>();
        for (StatementDuplicateReconciler.Pair pair : duplicateReconciler.pair(statementRows, existing, start, end, pairedIds)) {
            pairedIds.add(pair.existing().getId());
            if (pair.certain()) {
                certain.add(pair);
            } else {
                flagged.add(pair.statementRow().getId());
                reviewStatusManager.addReason(pair.statementRow(), ReviewReason.DUPLICATE_SUSPECT);
                reviewStatusManager.addReason(pair.existing(), ReviewReason.DUPLICATE_SUSPECT);
                touched.add(pair.existing());
            }
        }
        transactionRepository.saveAll(moving);
        transactionRepository.saveAll(touched);
        if (!certain.isEmpty()) {
            duplicateReconciler.mergeCertainAfterCommit(certain);
        }
        return flagged.size();
    }

    /** The account's transactions dated within the span of these ones, widened by {@code slackDays} each side. */
    private List<Transaction> window(UUID accountId, Collection<Transaction> txns, int slackDays) {
        if (txns.isEmpty()) {
            return List.of();
        }
        LocalDate min = minDate(txns);
        LocalDate max = maxDate(txns);
        if (min == null) {
            return List.of();
        }
        return transactionRepository.findByAccountIdAndDateRange(accountId, min.minusDays(slackDays), max.plusDays(slackDays));
    }

    private static LocalDate minDate(Collection<Transaction> txns) {
        return txns.stream().map(Transaction::getDate).filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate maxDate(Collection<Transaction> txns) {
        return txns.stream().map(Transaction::getDate).filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
    }

    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            out.add(ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
        return out;
    }
}
