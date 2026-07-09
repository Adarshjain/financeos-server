package com.financeos.domain.transaction;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

public class ReviewStatusManagerTest {

    private final ReviewStatusManager reviewStatusManager = new ReviewStatusManager();

    @Test
    public void testAddReason() {
        Transaction txn = new Transaction();
        txn.setReviewReasons(new HashSet<>());
        txn.setReviewType(ReviewType.AUTO_REVIEWED);

        reviewStatusManager.addReason(txn, ReviewReason.UNRECONCILED);

        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertTrue(txn.getReviewReasons().contains(ReviewReason.UNRECONCILED));
    }

    @Test
    public void testClearReason() {
        Transaction txn = new Transaction();
        txn.setReviewReasons(new HashSet<>());
        txn.getReviewReasons().add(ReviewReason.UNRECONCILED);
        txn.getReviewReasons().add(ReviewReason.CATEGORY_UNVERIFIED);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);

        // Clear one reason, still has another, should remain NEEDS_REVIEW
        reviewStatusManager.clearReason(txn, ReviewReason.UNRECONCILED, ReviewType.AUTO_REVIEWED);
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertFalse(txn.getReviewReasons().contains(ReviewReason.UNRECONCILED));
        assertTrue(txn.getReviewReasons().contains(ReviewReason.CATEGORY_UNVERIFIED));

        // Clear last reason, should promote to AUTO_REVIEWED
        reviewStatusManager.clearReason(txn, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.AUTO_REVIEWED);
        assertEquals(ReviewType.AUTO_REVIEWED, txn.getReviewType());
        assertTrue(txn.getReviewReasons().isEmpty());
    }

    @Test
    public void testClearAllReasons() {
        Transaction txn = new Transaction();
        txn.setReviewReasons(new HashSet<>());
        txn.getReviewReasons().add(ReviewReason.UNRECONCILED);
        txn.getReviewReasons().add(ReviewReason.CATEGORY_UNVERIFIED);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);

        reviewStatusManager.clearAllReasons(txn, ReviewType.MANUALLY_REVIEWED);

        assertEquals(ReviewType.MANUALLY_REVIEWED, txn.getReviewType());
        assertTrue(txn.getReviewReasons().isEmpty());
    }
}
