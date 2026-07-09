package com.financeos.domain.transaction;

import org.springframework.stereotype.Component;
import java.util.HashSet;

@Component
public class ReviewStatusManager {

    public void addReason(Transaction txn, ReviewReason reason) {
        if (txn.getReviewReasons() == null) {
            txn.setReviewReasons(new HashSet<>());
        }
        txn.getReviewReasons().add(reason);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
    }

    public void clearReason(Transaction txn, ReviewReason reason, ReviewType promoteTo) {
        if (txn.getReviewReasons() != null) {
            txn.getReviewReasons().remove(reason);
            if (txn.getReviewReasons().isEmpty()) {
                txn.setReviewType(promoteTo);
            }
        } else {
            txn.setReviewType(promoteTo);
        }
    }

    public void clearAllReasons(Transaction txn, ReviewType promoteTo) {
        if (txn.getReviewReasons() != null) {
            txn.getReviewReasons().clear();
        }
        txn.setReviewType(promoteTo);
    }
}
