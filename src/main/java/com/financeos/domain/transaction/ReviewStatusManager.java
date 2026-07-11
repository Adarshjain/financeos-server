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
        transitionTo(txn, ReviewType.NEEDS_REVIEW);
    }

    public void clearReason(Transaction txn, ReviewReason reason, ReviewType promoteTo) {
        if (txn.getReviewReasons() != null) {
            txn.getReviewReasons().remove(reason);
            if (txn.getReviewReasons().isEmpty()) {
                transitionTo(txn, promoteTo);
            }
        } else {
            transitionTo(txn, promoteTo);
        }
    }

    public void clearAllReasons(Transaction txn, ReviewType promoteTo) {
        if (txn.getReviewReasons() != null) {
            txn.getReviewReasons().clear();
        }
        transitionTo(txn, promoteTo);
    }

    public void transitionTo(Transaction txn, ReviewType targetType) {
        if (targetType == ReviewType.NEEDS_REVIEW) {
            if (txn.getReviewReasons() == null || txn.getReviewReasons().isEmpty()) {
                throw new com.financeos.core.exception.ValidationException("Transaction needs at least one review reason to transition to NEEDS_REVIEW");
            }
            txn.setReviewType(ReviewType.NEEDS_REVIEW);
            txn.setReviewedAt(null);
        } else if (targetType == ReviewType.MANUALLY_REVIEWED || targetType == ReviewType.AUTO_REVIEWED || targetType == ReviewType.NA) {
            if (txn.getReviewReasons() != null) {
                txn.getReviewReasons().clear();
            }
            txn.setReviewType(targetType);
            if (targetType == ReviewType.MANUALLY_REVIEWED || targetType == ReviewType.AUTO_REVIEWED) {
                txn.setReviewedAt(java.time.Instant.now());
            } else {
                txn.setReviewedAt(null);
            }
        }
    }
}
