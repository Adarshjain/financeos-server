package com.financeos.domain.account.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.AccountCardResponse;
import com.financeos.api.account.dto.CloseCardRequest;
import com.financeos.api.account.dto.CreateAccountCardRequest;
import com.financeos.api.account.dto.UpdateAccountCardRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class AccountCardServiceTest {

    @Mock
    private AccountCardRepository cardRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AccountCardService service;
    private UUID userId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountCardService(
                cardRepository,
                accountRepository,
                userRepository,
                eventPublisher
        );
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        account = new Account("HDFC Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createCard_success() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "5678")).thenReturn(Optional.empty());
        when(cardRepository.save(any(AccountCard.class))).thenAnswer(i -> {
            AccountCard c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CreateAccountCardRequest req = new CreateAccountCardRequest(
                "Spouse Card", "Jane Doe", CardRelationship.SPOUSE, "5678", LocalDate.of(2026, 1, 15), new BigDecimal("100000"), "Notes"
        );

        AccountCardResponse created = service.createCard(account.getId(), req);

        assertThat(created).isNotNull();
        assertThat(created.last4()).isEqualTo("5678");
        assertThat(created.holderName()).isEqualTo("Jane Doe");
        assertThat(created.relationship()).isEqualTo(CardRelationship.SPOUSE);
        assertThat(created.isPrimary()).isFalse();
        assertThat(created.closedOn()).isNull();
    }

    @Test
    void createCard_duplicateLast4OnSameAccount_throwsValidationException() {
        AccountCard existing = new AccountCard();
        existing.setLast4("5678");
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "5678")).thenReturn(Optional.of(existing));

        CreateAccountCardRequest req = new CreateAccountCardRequest(
                "Spouse Card", "Jane Doe", CardRelationship.SPOUSE, "5678", LocalDate.of(2026, 1, 15), null, null
        );

        assertThatThrownBy(() -> service.createCard(account.getId(), req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateCard_success() {
        AccountCard card = new AccountCard();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setUser(user);
        card.setHolderName("Old Name");
        card.setRelationship(CardRelationship.SELF);
        card.setLast4("5678");

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.save(any(AccountCard.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAccountCardRequest req = new UpdateAccountCardRequest(
                "New Label", "New Name", CardRelationship.CHILD, "5678", LocalDate.of(2026, 2, 1), null, null
        );

        AccountCardResponse updated = service.updateCard(account.getId(), card.getId(), req);

        assertThat(updated.label()).isEqualTo("New Label");
        assertThat(updated.holderName()).isEqualTo("New Name");
        assertThat(updated.relationship()).isEqualTo(CardRelationship.CHILD);
    }

    @Test
    void closeCard_setsStatusClosedAndReturnsResponse() {
        AccountCard card = new AccountCard();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setUser(user);
        card.setLast4("5678");
        card.setPrimary(false);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.save(any(AccountCard.class))).thenAnswer(i -> i.getArgument(0));

        LocalDate closedDate = LocalDate.of(2026, 8, 1);
        AccountCardResponse closed = service.closeCard(account.getId(), card.getId(), new CloseCardRequest(closedDate));

        assertThat(closed.closedOn()).isEqualTo(closedDate);
    }

    @Test
    void deleteCard_primaryCard_throwsValidationException() {
        AccountCard primaryCard = new AccountCard();
        primaryCard.setId(UUID.randomUUID());
        primaryCard.setAccount(account);
        primaryCard.setUser(user);
        primaryCard.setPrimary(true);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(primaryCard.getId())).thenReturn(Optional.of(primaryCard));

        assertThatThrownBy(() -> service.deleteCard(account.getId(), primaryCard.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Primary card cannot be deleted");
    }

    @Test
    void deleteCard_hasTransactions_throwsValidationException() {
        AccountCard card = new AccountCard();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setUser(user);
        card.setPrimary(false);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.countTransactionsByCardId(card.getId())).thenReturn(5L);

        assertThatThrownBy(() -> service.deleteCard(account.getId(), card.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot delete card with 5 transaction(s)");
    }

    @Test
    void deleteCard_emptyAddonCard_success() {
        AccountCard card = new AccountCard();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setUser(user);
        card.setPrimary(false);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.countTransactionsByCardId(card.getId())).thenReturn(0L);
        when(cardRepository.countRewardRulesByCardId(card.getId())).thenReturn(0L);
        when(cardRepository.countRewardMilestonesByCardId(card.getId())).thenReturn(0L);

        service.deleteCard(account.getId(), card.getId());

        verify(cardRepository).delete(card);
    }
}
