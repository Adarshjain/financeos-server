package com.financeos.domain.account.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.CardholderResponse;
import com.financeos.api.account.dto.CreateCardRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A card that is already closed can be added to a cardholder that has an active card (to record a number seen in old mail). */
@ExtendWith(MockitoExtension.class)
class CardholderServicePastCardTest {

    @Mock private CardholderRepository cardholderRepository;
    @Mock private CardRepository cardRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private RewardRuleRepository rewardRuleRepository;
    @Mock private RewardMilestoneRepository rewardMilestoneRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CardholderService service;
    private User user;
    private Account account;
    private Cardholder holder;

    @BeforeEach
    void setUp() {
        service = new CardholderService(cardholderRepository, cardRepository, accountRepository, userRepository,
                transactionRepository, rewardRuleRepository, rewardMilestoneRepository, eventPublisher);
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
        account = new Account("HDFC Savings", AccountType.bank_account);
        account.setId(UUID.randomUUID());
        account.setUser(user);

        holder = new Cardholder();
        holder.setId(UUID.randomUUID());
        holder.setUser(user);
        holder.setAccount(account);
        holder.setRole(CardholderRole.PRIMARY);
        holder.setOpenedOn(LocalDate.of(2020, 1, 1));
        Card active = new Card();
        active.setId(UUID.randomUUID());
        active.setUser(user);
        active.setAccount(account);
        active.setCardholder(holder);
        active.setLast4("1111");
        active.setIssuedOn(LocalDate.of(2025, 5, 1));
        holder.setCards(new ArrayList<>(List.of(active)));

        lenient().when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        lenient().when(cardholderRepository.findByIdAndAccountId(holder.getId(), account.getId())).thenReturn(Optional.of(holder));
        lenient().when(userRepository.getReferenceById(user.getId())).thenReturn(user);
        lenient().when(cardRepository.findOpenByAccountIdAndLast4(any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void pastCard_isAllowedWhileTheHolderHasAnActiveCard_andSettlesParkedMail() {
        CardholderResponse response = service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2021, 5, 1), null, LocalDate.of(2025, 4, 30)));

        assertThat(response.cards()).hasSize(2);
        assertThat(holder.getCards()).filteredOn(c -> "3003".equals(c.getLast4())).singleElement().satisfies(c -> {
            assertThat(c.getIssuedOn()).isEqualTo(LocalDate.of(2021, 5, 1));
            assertThat(c.getClosedOn()).isEqualTo(LocalDate.of(2025, 4, 30));
        });
        assertThat(holder.openCard()).get().extracting(Card::getLast4).isEqualTo("1111"); // the active card is untouched
        verify(eventPublisher).publishEvent(any(AccountIngestChangedEvent.class));
    }

    @Test
    void pastCard_cannotShareDigitsWithAnOpenCardOnTheAccount() {
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "1111")).thenReturn(Optional.of(new Card()));

        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("1111", LocalDate.of(2021, 5, 1), null, LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already active");
        verify(cardRepository, never()).save(any());
    }

    @Test
    void pastCard_closedInTheFuture_isRejected() {
        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2021, 5, 1), null, AppTime.today().plusDays(1))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("future");
        verify(cardRepository, never()).save(any());
    }

    @Test
    void pastCard_issuedAfterClosed_isRejected() {
        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2025, 5, 1), null, LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("after the closed date");
        verify(cardRepository, never()).save(any());
    }

    @Test
    void pastCard_withoutIssuedOn_isRejected() {
        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", null, null, LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("issued");
    }

    @Test
    void pastCard_closedOnToday_isAllowed() {
        CardholderResponse response = service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2021, 5, 1), null, AppTime.today()));

        assertThat(response.cards()).hasSize(2);
    }

    @Test
    void ordinaryCard_stillNeedsTheHolderToHaveNoActiveCard() {
        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2025, 5, 1))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already has an active card");
    }

    @Test
    void pastCard_onAClosedAccount_isStillRejected() {
        account.setClosedOn(LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> service.addCard(account.getId(), holder.getId(),
                new CreateCardRequest("3003", LocalDate.of(2021, 5, 1), null, LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("closed account");
    }
}
