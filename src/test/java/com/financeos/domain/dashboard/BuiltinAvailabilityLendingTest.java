package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The lending fact behind lending_balances: one memoised exists query; false without a repository. */
class BuiltinAvailabilityLendingTest {

    private final UUID userId = UUID.randomUUID();
    private final LendingRepository lendings = mock(LendingRepository.class);
    private final BuiltinAvailabilityService service = new BuiltinAvailabilityService(mock(AccountRepository.class),
            mock(LoanRepository.class), mock(HoldingRepository.class), lendings);

    @Test
    void hasLendingIsOneMemoisedQuery() {
        when(lendings.existsByUser_Id(userId)).thenReturn(true);
        BuiltinAvailability.Facts facts = service.factsFor(userId);
        assertTrue(facts.hasLending());
        assertTrue(facts.hasLending());
        verify(lendings, times(1)).existsByUser_Id(userId);
    }

    @Test
    void requiresLendingGivesItsReasonUntilAnEntryExists() {
        BuiltinAvailability check = BuiltinAvailability.requiresLending("Record a lending first");
        when(lendings.existsByUser_Id(userId)).thenReturn(false);
        assertEquals("Record a lending first", check.unavailableReason(service.factsFor(userId)));
        when(lendings.existsByUser_Id(userId)).thenReturn(true);
        assertNull(check.unavailableReason(service.factsFor(userId)));
    }

    @Test
    void withoutALendingRepositoryThereIsNoLending() {
        BuiltinAvailabilityService bare = new BuiltinAvailabilityService(mock(AccountRepository.class),
                mock(LoanRepository.class), mock(HoldingRepository.class));
        assertFalse(bare.factsFor(userId).hasLending());
    }
}
