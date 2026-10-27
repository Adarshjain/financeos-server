package com.financeos.domain.epf;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpfSuggestionMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 1);

    @Test
    void matchesAnExactReceivedAmountInsideTheWindowInclusive() {
        EpfEntry w = withdrawal(1000, 100); // received 900
        assertTrue(EpfSuggestionMatcher.fits(w, credit("900.00", DAY)));
        assertTrue(EpfSuggestionMatcher.fits(w, credit("900", DAY.plusDays(15))));
        assertFalse(EpfSuggestionMatcher.fits(w, credit("900", DAY.plusDays(16))));
        assertFalse(EpfSuggestionMatcher.fits(w, credit("900", DAY.minusDays(1))));
        assertFalse(EpfSuggestionMatcher.fits(w, credit("900.01", DAY)));
        Transaction debit = credit("900", DAY);
        debit.setType(TransactionType.DEBIT);
        assertFalse(EpfSuggestionMatcher.fits(w, debit));
    }

    @Test
    void picksTheClosestDateThenTheEarliestIdAndSkipsLinkedAndDismissedCredits() {
        EpfEntry w = withdrawal(1000, 0);
        Transaction far = credit("1000", DAY.plusDays(5));
        Transaction nearHigh = credit("1000", DAY.plusDays(2));
        Transaction nearLow = credit("1000", DAY.plusDays(2));
        if (nearLow.getId().compareTo(nearHigh.getId()) > 0) {
            Transaction t = nearLow;
            nearLow = nearHigh;
            nearHigh = t;
        }

        Map<UUID, Transaction> match = EpfSuggestionMatcher.match(List.of(w), List.of(far, nearHigh, nearLow), Set.of(), Map.of());
        assertEquals(nearLow.getId(), match.get(w.getId()).getId());

        Map<UUID, Transaction> afterLink = EpfSuggestionMatcher.match(List.of(w), List.of(far, nearHigh, nearLow),
                Set.of(nearLow.getId()), Map.of());
        assertEquals(nearHigh.getId(), afterLink.get(w.getId()).getId());

        Map<UUID, Transaction> afterDismiss = EpfSuggestionMatcher.match(List.of(w), List.of(far, nearHigh, nearLow),
                Set.of(nearLow.getId()), Map.of(w.getId(), Set.of(nearHigh.getId())));
        assertEquals(far.getId(), afterDismiss.get(w.getId()).getId());

        assertNull(EpfSuggestionMatcher.match(List.of(w), List.of(far), Set.of(), Map.of(w.getId(), Set.of(far.getId())))
                .get(w.getId()));
    }

    @Test
    void aCreditGoesToOneWithdrawalOnlyClosestDateFirstThenTheEarlierWithdrawal() {
        EpfEntry first = withdrawal(1000, 0);
        EpfEntry second = withdrawal(1000, 0);
        second.setCreditedOn(DAY.plusDays(2));
        Transaction credit = credit("1000", DAY.plusDays(3)); // 3 days from the first, 1 from the second

        Map<UUID, Transaction> match = EpfSuggestionMatcher.match(List.of(first, second), List.of(credit), Set.of(), Map.of());

        assertEquals(1, match.size());
        assertEquals(credit.getId(), match.get(second.getId()).getId());

        // two credits: each withdrawal gets its own, closest pairings first
        Transaction other = credit("1000", DAY.plusDays(1));
        Map<UUID, Transaction> two = EpfSuggestionMatcher.match(List.of(first, second), List.of(credit, other), Set.of(), Map.of());
        assertEquals(other.getId(), two.get(first.getId()).getId());
        assertEquals(credit.getId(), two.get(second.getId()).getId());

        // identical withdrawals compete for one credit: exactly one gets it, the lower id
        EpfEntry twinA = withdrawal(1000, 0);
        EpfEntry twinB = withdrawal(1000, 0);
        Transaction only = credit("1000", DAY.plusDays(4));
        Map<UUID, Transaction> tie = EpfSuggestionMatcher.match(List.of(twinA, twinB), List.of(only), Set.of(), Map.of());
        assertEquals(1, tie.size());
        UUID winner = twinA.getId().compareTo(twinB.getId()) < 0 ? twinA.getId() : twinB.getId();
        assertEquals(only.getId(), tie.get(winner).getId());
    }

    private static EpfEntry withdrawal(int gross, int tds) {
        EpfEntry e = new EpfEntry();
        e.setId(UUID.randomUUID());
        e.setType(EpfEntryType.WITHDRAWAL);
        e.setWithdrawalKind(EpfWithdrawalKind.PARTIAL);
        e.setCreditedOn(DAY);
        e.setEmployeeAmount(BigDecimal.valueOf(gross));
        e.setTdsAmount(tds == 0 ? null : BigDecimal.valueOf(tds));
        return e;
    }

    private static Transaction credit(String amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(TransactionType.CREDIT);
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        return t;
    }
}
