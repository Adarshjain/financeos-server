package com.financeos.domain.categorization;

import com.financeos.domain.transaction.*;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategorizationServiceVerifyResultTest {

    private final CategoryRuleRepository ruleRepo = mock(CategoryRuleRepository.class);
    private final TransactionRepository txnRepo = mock(TransactionRepository.class);
    private final CategorizationService service = new CategorizationService(
            ruleRepo, null, txnRepo, null, null, new ReviewStatusManager(), null);

    private CategoryRule rule(boolean verified) {
        CategoryRule r = new CategoryRule();
        r.setId(UUID.randomUUID());
        r.setVerified(verified);
        return r;
    }

    private Transaction txn(CategoryRule rule, ReviewReason... reasons) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAppliedRule(rule);
        t.setReviewType(ReviewType.NEEDS_REVIEW);
        t.setReviewReasons(new HashSet<>(Set.of(reasons)));
        return t;
    }

    @Test
    void returnsClearedIdsPerUnverifiedRule_onlyForRowsThatHadTheReason() {
        CategoryRule a = rule(false);
        CategoryRule b = rule(false);
        Transaction a1 = txn(a, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction a2 = txn(a, ReviewReason.UNRECONCILED);
        Transaction b1 = txn(b, ReviewReason.CATEGORY_UNVERIFIED);
        when(txnRepo.findByAppliedRuleIdIn(any())).thenReturn(List.of(a1, a2, b1));

        var result = service.verifyRules(List.of(a, b));

        assertEquals(2, result.size());
        assertEquals(a.getId(), result.get(0).ruleId());
        assertEquals(List.of(a1.getId()), result.get(0).clearedTransactionIds());
        assertEquals(List.of(b1.getId()), result.get(1).clearedTransactionIds());
        assertTrue(a.isVerified() && b.isVerified());
    }

    @Test
    void alreadyVerifiedRulesAreNotReported() {
        CategoryRule done = rule(true);
        when(txnRepo.findByAppliedRuleIdIn(any())).thenReturn(List.of());

        assertTrue(service.verifyRules(List.of(done)).isEmpty());
    }

    @Test
    void unverifyRule_restoresReasonOnlyOnRowsStillOnTheRule() {
        CategoryRule r = rule(true);
        CategoryRule elsewhere = rule(true);
        Transaction on = txn(r);
        Transaction off = txn(elsewhere);
        UUID uid = UUID.randomUUID();
        when(txnRepo.findAllByIdInAndUserId(any(), eq(uid))).thenReturn(List.of(on, off));

        var restored = service.unverifyRule(r, List.of(on.getId(), off.getId()), uid);

        assertEquals(List.of(on.getId()), restored);
        assertFalse(r.isVerified());
        assertTrue(on.getReviewReasons().contains(ReviewReason.CATEGORY_UNVERIFIED));
        assertTrue(off.getReviewReasons().isEmpty());
    }
}
