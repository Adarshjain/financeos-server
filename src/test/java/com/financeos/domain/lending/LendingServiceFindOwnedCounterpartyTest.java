package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link LendingService#findOwnedCounterparty}: the user's counterparty with its ledger totals, else empty. */
class LendingServiceFindOwnedCounterpartyTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private CounterpartyRepository counterpartyRepository;
    private LendingRepository lendingRepository;
    private LendingService lendingService;

    @BeforeEach
    void setUp() {
        counterpartyRepository = mock(CounterpartyRepository.class);
        lendingRepository = mock(LendingRepository.class);
        lendingService = new LendingService(counterpartyRepository, lendingRepository, mock(UserRepository.class),
                mock(TransactionReferenceValidator.class), mock(TransactionRepository.class),
                mock(TransactionLinkRepository.class));
        UserContext.setCurrentUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void ownCounterpartyComesWithTheTotalsItIsListedWith() {
        Counterparty cp = counterparty(USER_ID);
        when(lendingRepository.findByCounterparty_Id(cp.getId())).thenReturn(List.of(
                entry(LendingDirection.lent, LendingKind.principal, "5000"),
                entry(LendingDirection.borrowed, LendingKind.settlement, "2000"),
                entry(LendingDirection.borrowed, LendingKind.principal, "500"),
                entry(LendingDirection.lent, LendingKind.settlement, "100")));

        CounterpartyResponse found = lendingService.findOwnedCounterparty(cp.getId()).orElseThrow();

        assertEquals(cp.getId(), found.id());
        assertEquals("Asha", found.name());
        assertEquals(new BigDecimal("5000"), found.totalLent());
        assertEquals(new BigDecimal("500"), found.totalBorrowed());
        assertEquals(new BigDecimal("2000"), found.repaidToYou());
        assertEquals(new BigDecimal("100"), found.repaidByYou());
        assertEquals(new BigDecimal("2600"), found.netPosition());
        assertEquals(4, found.entryCount());
    }

    @Test
    void someoneElsesCounterpartyIsEmptyAndItsLedgerNeverRead() {
        Counterparty cp = counterparty(UUID.randomUUID());

        assertTrue(lendingService.findOwnedCounterparty(cp.getId()).isEmpty());
        verifyNoInteractions(lendingRepository);
    }

    @Test
    void counterpartyWithoutAnOwnerIsEmpty() {
        Counterparty cp = counterparty(null);
        cp.setUser(null);

        assertTrue(lendingService.findOwnedCounterparty(cp.getId()).isEmpty());
    }

    @Test
    void missingCounterpartyIsEmpty() {
        UUID id = UUID.randomUUID();
        when(counterpartyRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(lendingService.findOwnedCounterparty(id).isEmpty());
    }

    private Counterparty counterparty(UUID ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName("Asha");
        cp.setUser(owner);
        when(counterpartyRepository.findById(cp.getId())).thenReturn(Optional.of(cp));
        return cp;
    }

    private static Lending entry(LendingDirection direction, LendingKind kind, String amount) {
        Lending l = new Lending();
        l.setDirection(direction);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        return l;
    }
}
