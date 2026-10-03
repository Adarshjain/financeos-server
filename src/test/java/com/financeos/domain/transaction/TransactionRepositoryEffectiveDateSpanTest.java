package com.financeos.domain.transaction;

import com.financeos.domain.transaction.TransactionRepository.EffectiveDateSpan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

/** The default method {@code effectiveDateSpan}: earliest effective date through max(today, latest). */
class TransactionRepositoryEffectiveDateSpanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private final UUID id = UUID.randomUUID();
    private final TransactionRepository repo = mock(TransactionRepository.class, CALLS_REAL_METHODS);

    private EffectiveDateSpan span(LocalDate min, LocalDate max) {
        doReturn(min).when(repo).findMinEffectiveDateByAccountId(id);
        doReturn(max).when(repo).findMaxEffectiveDateByAccountId(id);
        return repo.effectiveDateSpan(id, TODAY);
    }

    @Test
    void noTransactionsGivesNullWithoutAskingForTheMax() {
        assertNull(span(null, null));
        verify(repo, never()).findMaxEffectiveDateByAccountId(any());
    }

    @Test
    void pastTransactionsEndToday() {
        assertEquals(new EffectiveDateSpan(LocalDate.of(2026, 1, 5), TODAY), span(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 1)));
    }

    @Test
    void latestOnTodayEndsToday() {
        assertEquals(new EffectiveDateSpan(LocalDate.of(2026, 1, 5), TODAY), span(LocalDate.of(2026, 1, 5), TODAY));
    }

    @Test
    void aNullMaxEndsToday() {
        assertEquals(new EffectiveDateSpan(LocalDate.of(2026, 1, 5), TODAY), span(LocalDate.of(2026, 1, 5), null));
    }

    @Test
    void aFutureDatedLatestExtendsTheSpan() {
        assertEquals(new EffectiveDateSpan(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 4, 20)),
                span(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 4, 20)));
    }

    @Test
    void aFutureMinWithoutAFutureMaxIsClampedToToday() {
        assertEquals(new EffectiveDateSpan(TODAY, TODAY), span(LocalDate.of(2026, 5, 1), null));
    }

    @Test
    void aFutureMinWithAnEqualFutureMaxKeepsBoth() {
        LocalDate future = LocalDate.of(2026, 5, 1);
        assertEquals(new EffectiveDateSpan(future, future), span(future, future));
    }
}
