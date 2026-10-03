package com.financeos.domain.account.card;

import com.financeos.core.time.AppTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CardholderRepositoryTest {

    @AfterEach
    void reset() {
        AppTime.reset();
    }

    @Test
    void findOpenByAccountIdDelegatesWithTheBusinessDate() {
        // 20:00 UTC on 10 March is already 11 March in India
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        CardholderRepository repo = mock(CardholderRepository.class, CALLS_REAL_METHODS);
        UUID accountId = UUID.randomUUID();
        List<Cardholder> open = List.of(new Cardholder());
        doReturn(open).when(repo).findOpenByAccountIdAsOf(accountId, LocalDate.of(2026, 3, 11));

        assertEquals(open, repo.findOpenByAccountId(accountId));

        verify(repo).findOpenByAccountIdAsOf(accountId, LocalDate.of(2026, 3, 11));
    }
}
