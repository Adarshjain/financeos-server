package com.financeos.domain.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Accepting a LENDING link suggestion goes through the existing settlement logic, for the owner only. */
class LendingRepaymentSuggestionTest {

    private final LendingRepository lendingRepository = mock(LendingRepository.class);
    private final LendingService service = spy(new LendingService(mock(CounterpartyRepository.class), lendingRepository,
            mock(UserRepository.class), mock(TransactionReferenceValidator.class), mock(TransactionRepository.class),
            mock(TransactionLinkRepository.class)));
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Lending lending(UUID ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setUser(owner);
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setUser(owner);
        l.setCounterparty(cp);
        l.setDirection(LendingDirection.lent);
        l.setAmount(new BigDecimal("8000"));
        l.setEntryDate(LocalDate.of(2026, 10, 1));
        when(lendingRepository.findById(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    @Test
    void settleFromSuggestionRecordsASettlementForTheEntrysPerson() {
        UserContext.setCurrentUserId(userId);
        Lending l = lending(userId);
        UUID txn = UUID.randomUUID();
        doReturn(null).when(service).recordSettlement(l.getCounterparty().getId(), txn);

        service.settleFromSuggestion(l.getId(), txn);

        verify(service).recordSettlement(l.getCounterparty().getId(), txn);
    }

    @Test
    void anotherUsersEntryCannotBeSettledOrResolved() {
        UserContext.setCurrentUserId(userId);
        Lending foreign = lending(UUID.randomUUID());
        when(lendingRepository.findAllById(any())).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.settleFromSuggestion(foreign.getId(), UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.counterpartyIdsOf(List.of(foreign.getId()))).isEmpty();
        verify(service, never()).recordSettlement(any(), any());
    }
}
