package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.loan.LoanRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-user availability of built-in widgets: lazy, memoised facts and the stock checks. */
class BuiltinAvailabilityServiceTest {

    private final UUID userId = UUID.randomUUID();
    private AccountRepository accounts;
    private LoanRepository loans;
    private HoldingRepository holdings;
    private BuiltinAvailabilityService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        loans = mock(LoanRepository.class);
        holdings = mock(HoldingRepository.class);
        service = new BuiltinAvailabilityService(accounts, loans, holdings);
    }

    private static BuiltinWidgetRegistry.Entry entry(BuiltinAvailability availability) {
        return new BuiltinWidgetRegistry.Entry("k", "K", "d", BuiltinWidgetRegistry.CATEGORY_OVERVIEW, null, null, null,
                50, BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null, null, List.of(), null, availability);
    }

    @Test
    void factsAreNotQueriedUntilAsked() {
        service.factsFor(userId);
        verifyNoInteractions(accounts, loans, holdings);
    }

    @Test
    void alwaysIsUsableWithoutAnyQuery() {
        assertNull(service.unavailableReason(entry(BuiltinAvailability.ALWAYS), service.factsFor(userId)));
        verifyNoInteractions(accounts, loans, holdings);
    }

    @Test
    void aNullCheckCountsAsUsable() {
        assertNull(service.unavailableReason(entry(null), service.factsFor(userId)));
    }

    @Test
    void accountTypeFactIsQueriedOncePerTypeAndScopedToTheUser() {
        when(accounts.existsByUser_IdAndType(userId, AccountType.credit_card)).thenReturn(true);
        when(accounts.existsByUser_IdAndType(userId, AccountType.broker)).thenReturn(false);
        BuiltinAvailability.Facts facts = service.factsFor(userId);

        assertTrue(facts.hasAccountOfType(AccountType.credit_card));
        assertTrue(facts.hasAccountOfType(AccountType.credit_card));
        assertFalse(facts.hasAccountOfType(AccountType.broker));

        verify(accounts, times(1)).existsByUser_IdAndType(userId, AccountType.credit_card);
        verify(accounts, times(1)).existsByUser_IdAndType(userId, AccountType.broker);
    }

    @Test
    void anyAccountLoanAndHoldingFactsAreMemoised() {
        when(accounts.existsByUser_Id(userId)).thenReturn(true);
        when(loans.existsByUser_Id(userId)).thenReturn(false);
        when(holdings.existsByUser_Id(userId)).thenReturn(true);
        BuiltinAvailability.Facts facts = service.factsFor(userId);

        for (int i = 0; i < 3; i++) {
            assertTrue(facts.hasAnyAccount());
            assertFalse(facts.hasLoan());
            assertTrue(facts.hasHoldings());
        }
        verify(accounts, times(1)).existsByUser_Id(userId);
        verify(loans, times(1)).existsByUser_Id(userId);
        verify(holdings, times(1)).existsByUser_Id(userId);
    }

    @Test
    void eachRequestGetsFreshFacts() {
        when(loans.existsByUser_Id(userId)).thenReturn(false, true);
        assertFalse(service.factsFor(userId).hasLoan());
        assertTrue(service.factsFor(userId).hasLoan());
    }

    @Test
    void requiresAccountOfTypeGivesItsReasonUntilTheUserHasOne() {
        BuiltinWidgetRegistry.Entry cards = entry(
                BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, "Add a credit card first"));
        when(accounts.existsByUser_IdAndType(userId, AccountType.credit_card)).thenReturn(false);
        assertEquals("Add a credit card first", service.unavailableReason(cards, service.factsFor(userId)));

        when(accounts.existsByUser_IdAndType(userId, AccountType.credit_card)).thenReturn(true);
        assertNull(service.unavailableReason(cards, service.factsFor(userId)));
    }

    @Test
    void requiresAnyAccountLoanAndHoldingsGiveTheirReasons() {
        BuiltinAvailability.Facts none = service.factsFor(userId);
        assertEquals("Add an account first",
                service.unavailableReason(entry(BuiltinAvailability.requiresAnyAccount("Add an account first")), none));
        assertEquals("Add a loan first",
                service.unavailableReason(entry(BuiltinAvailability.requiresLoan("Add a loan first")), none));
        assertEquals("Add investments first",
                service.unavailableReason(entry(BuiltinAvailability.requiresHoldings("Add investments first")), none));

        when(accounts.existsByUser_Id(userId)).thenReturn(true);
        when(loans.existsByUser_Id(userId)).thenReturn(true);
        when(holdings.existsByUser_Id(userId)).thenReturn(true);
        BuiltinAvailability.Facts all = service.factsFor(userId);
        assertNull(service.unavailableReason(entry(BuiltinAvailability.requiresAnyAccount("x")), all));
        assertNull(service.unavailableReason(entry(BuiltinAvailability.requiresLoan("x")), all));
        assertNull(service.unavailableReason(entry(BuiltinAvailability.requiresHoldings("x")), all));
    }
}
