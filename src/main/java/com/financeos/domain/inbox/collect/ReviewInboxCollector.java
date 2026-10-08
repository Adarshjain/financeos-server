package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.TransactionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The review queue as one summary row: how many transactions wait, and how many of those for a category. */
@Component
public class ReviewInboxCollector implements InboxCollector {

    static final String REVIEW_HREF = "/transactions/review";

    private final TransactionRepository transactionRepository;

    public ReviewInboxCollector(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        long total = transactionRepository.countByUserIdAndReviewType(userId, ReviewType.NEEDS_REVIEW);
        if (total <= 0) {
            return List.of();
        }
        long category = transactionRepository.countByUserIdAndReviewTypeAndReason(userId, ReviewType.NEEDS_REVIEW,
                ReviewReason.CATEGORY_UNVERIFIED);
        int n = (int) Math.min(total, Integer.MAX_VALUE);
        String subtitle = category > 0
                ? category + " flagged for category" + (category < total ? " · " + (total - category) + " other" : "")
                : n + " waiting for review";
        return List.of(InboxRows.summary(InboxKinds.KEY_REVIEW, InboxKinds.REVIEW, SEVERITY_WARNING, SECTION_NEEDS_LOOK,
                "Transactions to review", subtitle, REVIEW_HREF, n,
                List.of(InboxActionResponse.navigate("review", "Review", REVIEW_HREF))));
    }
}
