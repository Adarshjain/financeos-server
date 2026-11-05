package com.financeos.domain.nps.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.nps.link.NpsLinkMatcher.Dismissals;
import com.financeos.domain.nps.link.NpsLinkMatcher.Entry;
import com.financeos.domain.nps.link.NpsLinkMatcher.Payment;
import com.financeos.domain.nps.link.NpsLinkMatcher.Plan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpsLinkMatcherTest {

    private static final LocalDate POSTED = LocalDate.of(2026, 11, 10);
    private static final UUID ACC = UUID.randomUUID();

    private static Entry entry(String amount) {
        return new Entry(UUID.randomUUID(), ACC, NpsTier.I, new BigDecimal(amount), POSTED);
    }

    private static Payment payment(String amount, int daysBefore) {
        return new Payment(UUID.randomUUID(), ACC, "Bank", POSTED.minusDays(daysBefore), new BigDecimal(amount), "NPS TRUST");
    }

    @Test
    void oneEntryAndOnePaymentThatFitOnlyEachOtherAreUnique() {
        Entry e = entry("1000");
        Payment p = payment("1010", 2);
        Plan plan = NpsLinkMatcher.plan(List.of(e), List.of(p), Dismissals.NONE);
        assertEquals(1, plan.unique().size());
        assertEquals(p.id(), plan.unique().get(0).payment().id());
        assertTrue(plan.ambiguous().isEmpty());
        assertTrue(plan.unmatched().isEmpty());
    }

    @Test
    void twoPaymentsForOneEntryAreBothAmbiguous() {
        Entry e = entry("1000");
        Plan plan = NpsLinkMatcher.plan(List.of(e), List.of(payment("1000", 1), payment("1005", 3)), Dismissals.NONE);
        assertTrue(plan.unique().isEmpty());
        assertEquals(2, plan.ambiguous().size());
    }

    @Test
    void twoEntriesForOnePaymentAreBothAmbiguous() {
        Plan plan = NpsLinkMatcher.plan(List.of(entry("1000"), entry("1000")), List.of(payment("1000", 1)), Dismissals.NONE);
        assertTrue(plan.unique().isEmpty());
        assertEquals(2, plan.ambiguous().size());
    }

    @Test
    void aPaymentThatFitsNothingIsUnmatched() {
        Payment far = payment("1000", 20);
        Plan plan = NpsLinkMatcher.plan(List.of(entry("1000")), List.of(far), Dismissals.NONE);
        assertEquals(List.of(far), plan.unmatched());
    }

    @Test
    void aPairDismissalRemovesThePairAndANotNpsDismissalRemovesThePaymentEntirely() {
        Entry e = entry("1000");
        Payment a = payment("1000", 1);
        Payment b = payment("1000", 2);
        Dismissals pair = new Dismissals(Set.of(), Set.of(Dismissals.pairKey(e.id(), a.id())));
        Plan afterPair = NpsLinkMatcher.plan(List.of(e), List.of(a, b), pair);
        assertEquals(1, afterPair.unique().size());
        assertEquals(b.id(), afterPair.unique().get(0).payment().id());
        assertEquals(List.of(a), afterPair.unmatched());

        Plan afterNotNps = NpsLinkMatcher.plan(List.of(e), List.of(a, b), new Dismissals(Set.of(a.id()), Set.of()));
        assertEquals(b.id(), afterNotNps.unique().get(0).payment().id());
        assertTrue(afterNotNps.unmatched().isEmpty());
    }
}
