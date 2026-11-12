package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.DuplicateReviewItem;
import com.financeos.api.transaction.dto.DuplicateReviewTransaction;
import com.financeos.core.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The review queue's "Duplicate" rows, each with its likely twin found by {@link TransactionMatcher} at read time. */
@Service
public class DuplicateReviewService {

    private final TransactionRepository transactionRepository;
    private final TransactionMatcher transactionMatcher;

    public DuplicateReviewService(TransactionRepository transactionRepository, TransactionMatcher transactionMatcher) {
        this.transactionRepository = transactionRepository;
        this.transactionMatcher = transactionMatcher;
    }

    @Transactional(readOnly = true)
    public Page<DuplicateReviewItem> duplicates(Collection<UUID> ids, Pageable pageable) {
        UUID userId = UserContext.getCurrentUserId();
        Page<Transaction> page = ids == null
                ? transactionRepository.findNeedsReviewWithReason(userId, ReviewReason.DUPLICATE_SUSPECT, pageable)
                : transactionRepository.findNeedsReviewWithReasonAmongIds(userId, ReviewReason.DUPLICATE_SUSPECT, ids, pageable);
        List<Transaction> flagged = page.getContent();
        if (flagged.isEmpty()) {
            return page.map(t -> null);
        }
        Set<UUID> accountIds = new HashSet<>();
        Set<LocalDate> dates = new HashSet<>();
        Set<java.math.BigDecimal> amounts = new HashSet<>();
        for (Transaction t : flagged) {
            if (t.getAccount() != null && t.getDate() != null && t.getAmount() != null) {
                accountIds.add(t.getAccount().getId());
                dates.add(t.getDate());
                amounts.add(t.getAmount());
            }
        }
        List<Transaction> candidates = accountIds.isEmpty() ? List.of()
                : transactionRepository.findDuplicateCandidates(userId, accountIds, dates, amounts);
        return page.map(t -> new DuplicateReviewItem(side(t), twinOf(t, candidates)));
    }

    /** The look-alike (window 0, as the duplicate flag is raised) preferring an unflagged row, then the earliest created. */
    private DuplicateReviewTransaction twinOf(Transaction t, List<Transaction> candidates) {
        if (t.getAccount() == null) {
            return null;
        }
        return candidates.stream()
                .filter(c -> !c.getId().equals(t.getId()) && c.getAccount() != null
                        && c.getAccount().getId().equals(t.getAccount().getId())
                        && transactionMatcher.areDuplicates(t, c, 0))
                .min(Comparator
                        .comparing((Transaction c) -> c.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT))
                        .thenComparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Transaction::getId))
                .map(DuplicateReviewService::side)
                .orElse(null);
    }

    private static DuplicateReviewTransaction side(Transaction t) {
        return new DuplicateReviewTransaction(t.getId(), t.getDate(), t.getAmount(), t.getDescription(),
                t.getAccount() == null ? null : t.getAccount().getName(), t.getSource());
    }
}
