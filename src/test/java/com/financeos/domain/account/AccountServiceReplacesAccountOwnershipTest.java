package com.financeos.domain.account;

import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A credit card can only be marked as replacing a card account of the same owner: another user's
 * account id reads as not found (like a missing id), and no link to it is stored.
 */
class AccountServiceReplacesAccountOwnershipTest {

    private AccountRepository accountRepository;
    private AccountService accountService;

    private final UUID me = UUID.randomUUID();
    private Account myNewCard;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        accountService = new AccountService(accountRepository, mock(CardholderRepository.class),
                mock(CardRepository.class), mock(UserRepository.class), mock(StatementRepository.class),
                mock(TransactionRepository.class), mock(HoldingValuationService.class),
                mock(GmailBackfillDemandRepository.class), mock(ApplicationEventPublisher.class));

        User owner = new User();
        owner.setId(me);
        myNewCard = new Account();
        myNewCard.setType(AccountType.credit_card);
        myNewCard.setUser(owner);
    }

    @Test
    void replacingMyOwnOldCardLinksIt() {
        Account myOldCard = new Account();
        myOldCard.setId(UUID.randomUUID());
        when(accountRepository.findByIdAndUser_Id(myOldCard.getId(), me)).thenReturn(Optional.of(myOldCard));

        accountService.addCreditCardDetails(myNewCard, request(myOldCard.getId()));

        assertSame(myOldCard, myNewCard.getReplacesAccount());
    }

    @Test
    void replacingAnotherUsersAccountIsNotFoundAndLinksNothing() {
        UUID theirAccount = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(theirAccount, me)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accountService.addCreditCardDetails(myNewCard, request(theirAccount)));

        verify(accountRepository).findByIdAndUser_Id(theirAccount, me);
        verify(accountRepository, never()).findById(any());
        assertNull(myNewCard.getReplacesAccount());
    }

    @Test
    void noReplacedAccountLooksNothingUp() {
        accountService.addCreditCardDetails(myNewCard, request(null));

        verify(accountRepository, never()).findByIdAndUser_Id(any(), any());
        assertNull(myNewCard.getReplacesAccount());
    }

    private static CreateAccountRequest.CreditCardRequest request(UUID replacesAccountId) {
        return new CreateAccountRequest.CreditCardRequest("Card", AccountType.credit_card, null, null, null,
                "1234", new BigDecimal("100000"), LocalDate.of(2026, 1, 1), null, null, replacesAccountId,
                null, null);
    }
}
