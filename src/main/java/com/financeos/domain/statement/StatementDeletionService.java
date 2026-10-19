package com.financeos.domain.statement;

import com.financeos.api.statement.dto.StatementDeletionPreviewResponse;
import com.financeos.api.statement.dto.StatementDeletionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.observability.AuditLogger;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.inbox.InboxItemStateRepository;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deletes a statement and what its ingestion left behind, so the same file can be ingested again
 * (a corrected parse, or a statement uploaded to the wrong account).
 *
 * <ul>
 *   <li>The statement row; its line links and card-bill details (due date, paid mark, bill
 *       notification log) go with it.</li>
 *   <li>Transactions the statement created — a file upload's rows, a Gmail statement's
 *       {@code <messageId>:<line>} rows — unless another statement also has a line for them.
 *       Deleted through {@link TransactionService#batchDelete}, so transfer links dissolve and
 *       loan/lending/dividend references are unlinked exactly as on a manual delete.</li>
 *   <li>Transactions it only matched (email alerts, manual entries, rows another statement
 *       created) stay. An email alert no longer backed by any statement goes back to review as
 *       {@link ReviewReason#UNRECONCILED}, the state it had before the statement arrived.</li>
 *   <li>A surviving transaction flagged {@link ReviewReason#DUPLICATE_SUSPECT} whose only
 *       look-alike was deleted loses that flag.</li>
 *   <li>The account's last statement date falls back to the latest remaining statement, and the
 *       inbox snooze/dismiss state of the statement's bill is dropped.</li>
 * </ul>
 * Billing cycles, bills, balance anchors and reward windows are read from statements on demand
 * and need no cleanup. A Gmail statement is not fetched again on its own; uploading its PDF
 * re-imports it.
 */
@Service
public class StatementDeletionService {

    private static final int IN_CHUNK = 500;

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final TransactionLinkRepository transactionLinkRepository;
    private final ObligationRefService obligationRefService;
    private final TransactionMatcher transactionMatcher;
    private final ReviewStatusManager reviewStatusManager;
    private final InboxItemStateRepository inboxItemStateRepository;
    private final AuditLogger auditLogger;

    public StatementDeletionService(AccountRepository accountRepository,
                                    StatementRepository statementRepository,
                                    StatementTransactionRepository statementTransactionRepository,
                                    TransactionRepository transactionRepository,
                                    TransactionService transactionService,
                                    TransactionLinkRepository transactionLinkRepository,
                                    ObligationRefService obligationRefService,
                                    TransactionMatcher transactionMatcher,
                                    ReviewStatusManager reviewStatusManager,
                                    InboxItemStateRepository inboxItemStateRepository,
                                    AuditLogger auditLogger) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.transactionLinkRepository = transactionLinkRepository;
        this.obligationRefService = obligationRefService;
        this.transactionMatcher = transactionMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.inboxItemStateRepository = inboxItemStateRepository;
        this.auditLogger = auditLogger;
    }

    private record Plan(Account account, Statement statement, List<Transaction> toDelete,
                        List<Transaction> kept, List<Transaction> alertsToReopen) {
    }

    @Transactional(readOnly = true)
    public StatementDeletionPreviewResponse preview(UUID accountId, UUID statementId) {
        Plan plan = plan(accountId, statementId);
        return new StatementDeletionPreviewResponse(plan.toDelete().size(), plan.kept().size(),
                countLinkedToRecords(ids(plan.toDelete())), plan.alertsToReopen().size());
    }

    @Transactional
    public StatementDeletionResponse delete(UUID accountId, UUID statementId) {
        Plan plan = plan(accountId, statementId);
        Account account = plan.account();
        Statement statement = plan.statement();
        List<UUID> deleteIds = ids(plan.toDelete());

        // Decided before anything is deleted: survivors that were flagged as a duplicate of a row
        // that is about to go.
        List<Transaction> flaggedPartners = duplicateSuspectPartners(account.getId(), plan.toDelete());

        for (Transaction alert : plan.alertsToReopen()) {
            reviewStatusManager.addReason(alert, ReviewReason.UNRECONCILED);
        }
        transactionRepository.saveAll(plan.alertsToReopen());

        statementTransactionRepository.deleteByStatementId(statement.getId());
        statementRepository.delete(statement);
        for (List<UUID> chunk : chunks(deleteIds)) {
            transactionService.batchDelete(chunk);
        }

        int flagsCleared = clearOrphanedDuplicateFlags(account.getId(), flaggedPartners, new HashSet<>(deleteIds));

        inboxItemStateRepository.findByUserIdAndItemKey(account.getUser().getId(), InboxKinds.billKey(statement.getId()))
                .ifPresent(inboxItemStateRepository::delete);

        account.setLastStatementDate(statementRepository.findLatestPeriodEnd(account.getId()));
        accountRepository.save(account);

        auditLogger.mutation("Statement", statement.getId(), "DELETE", "user:" + UserContext.getCurrentUserId(),
                "manual", List.of("transactions"), deleteIds.size(), null, "INR");

        return new StatementDeletionResponse(deleteIds.size(), plan.kept().size(), plan.alertsToReopen().size(),
                flagsCleared);
    }

    private Plan plan(UUID accountId, UUID statementId) {
        UUID userId = UserContext.getCurrentUserId();
        // Another user's account or statement answers exactly like a missing one.
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        Statement statement = statementRepository.findByIdAndAccount_Id(statementId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));

        List<UUID> linkedIds = statementTransactionRepository.findTransactionIdsByStatementId(statementId);
        List<Transaction> linked = new ArrayList<>();
        Set<UUID> elsewhere = new HashSet<>();
        for (List<UUID> chunk : chunks(linkedIds)) {
            linked.addAll(transactionRepository.findAllByIdInAndUserId(chunk, userId));
            elsewhere.addAll(statementTransactionRepository.findLinkedToOtherStatements(chunk, statementId));
        }

        List<Transaction> toDelete = new ArrayList<>();
        List<Transaction> kept = new ArrayList<>();
        List<Transaction> alertsToReopen = new ArrayList<>();
        for (Transaction t : linked) {
            if (t.getAccount() == null || !accountId.equals(t.getAccount().getId())) {
                continue;
            }
            boolean onlyThisStatement = !elsewhere.contains(t.getId());
            if (onlyThisStatement && createdBy(statement, t)) {
                toDelete.add(t);
                continue;
            }
            kept.add(t);
            if (onlyThisStatement && t.getSource() == TransactionSource.gmail_transaction_alert
                    && !t.getReviewReasons().contains(ReviewReason.UNRECONCILED)) {
                alertsToReopen.add(t);
            }
        }
        return new Plan(account, statement, toDelete, kept, alertsToReopen);
    }

    private static boolean createdBy(Statement statement, Transaction t) {
        return statement.createdTransaction(t.getSource(), t.getSourceMessageId());
    }

    private int countLinkedToRecords(List<UUID> deleteIds) {
        Set<UUID> referenced = new HashSet<>();
        Set<UUID> deleting = new HashSet<>(deleteIds);
        for (List<UUID> chunk : chunks(deleteIds)) {
            referenced.addAll(obligationRefService.refsFor(chunk).keySet());
            for (TransactionLink link : transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(chunk)) {
                for (TransactionLinkMember member : link.getMembers()) {
                    UUID id = member.getTransaction().getId();
                    if (deleting.contains(id)) {
                        referenced.add(id);
                    }
                }
            }
        }
        return referenced.size();
    }

    private List<Transaction> duplicateSuspectPartners(UUID accountId, List<Transaction> toDelete) {
        if (toDelete.isEmpty()) {
            return List.of();
        }
        Set<UUID> deleting = new HashSet<>(ids(toDelete));
        List<Transaction> partners = new ArrayList<>();
        for (Transaction survivor : window(accountId, toDelete)) {
            if (deleting.contains(survivor.getId())
                    || !survivor.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT)) {
                continue;
            }
            if (toDelete.stream().anyMatch(d -> transactionMatcher.areDuplicates(d, survivor, 0))) {
                partners.add(survivor);
            }
        }
        return partners;
    }

    private int clearOrphanedDuplicateFlags(UUID accountId, List<Transaction> partners, Set<UUID> deletedIds) {
        if (partners.isEmpty()) {
            return 0;
        }
        List<Transaction> remaining = window(accountId, partners).stream()
                .filter(t -> !deletedIds.contains(t.getId()))
                .toList();
        List<Transaction> cleared = new ArrayList<>();
        for (Transaction partner : partners) {
            boolean stillHasLookAlike = remaining.stream()
                    .anyMatch(r -> !r.getId().equals(partner.getId()) && transactionMatcher.areDuplicates(r, partner, 0));
            if (!stillHasLookAlike) {
                reviewStatusManager.clearReason(partner, ReviewReason.DUPLICATE_SUSPECT, ReviewType.AUTO_REVIEWED);
                cleared.add(partner);
            }
        }
        transactionRepository.saveAll(cleared);
        return cleared.size();
    }

    /** The account's transactions dated within the span of these ones (duplicates are same-day only). */
    private List<Transaction> window(UUID accountId, Collection<Transaction> txns) {
        LocalDate min = txns.stream().map(Transaction::getDate).filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate max = txns.stream().map(Transaction::getDate).filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
        if (min == null) {
            return List.of();
        }
        return transactionRepository.findByAccountIdAndDateRange(accountId, min, max);
    }

    private static List<UUID> ids(List<Transaction> txns) {
        return txns.stream().map(Transaction::getId).toList();
    }

    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            out.add(ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
        return out;
    }
}
