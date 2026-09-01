package com.financeos.domain.account.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
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
class CardholderServiceTest {

    @Mock private CardholderRepository cardholderRepository;
    @Mock private CardRepository cardRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private RewardRuleRepository rewardRuleRepository;
    @Mock private RewardMilestoneRepository rewardMilestoneRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CardholderService service;
    private UUID userId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new CardholderService(
                cardholderRepository,
                cardRepository,
                accountRepository,
                userRepository,
                transactionRepository,
                rewardRuleRepository,
                rewardMilestoneRepository,
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
    void addAddon_success_withInitialCard() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "5678")).thenReturn(Optional.empty());
        when(cardholderRepository.save(any(Cardholder.class))).thenAnswer(i -> {
            Cardholder ch = i.getArgument(0);
            ch.setId(UUID.randomUUID());
            return ch;
        });

        CreateCardholderRequest req = new CreateCardholderRequest(
                "Jane Doe", CardholderRelationship.SPOUSE, new BigDecimal("100000"), "5678", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15)
        );

        CardholderResponse response = service.addAddon(account.getId(), req);

        assertThat(response).isNotNull();
        assertThat(response.personName()).isEqualTo("Jane Doe");
        assertThat(response.role()).isEqualTo(CardholderRole.ADDON);
        assertThat(response.relationship()).isEqualTo(CardholderRelationship.SPOUSE);
        assertThat(response.spendLimit()).isEqualByComparingTo("100000");
        assertThat(response.currentLast4()).isEqualTo("5678");
        assertThat(response.cards()).hasSize(1);
    }

    @Test
    void addAddon_fails_duplicateOpenLast4() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "1234")).thenReturn(Optional.of(new Card()));

        CreateCardholderRequest req = new CreateCardholderRequest(
                "Jane Doe", CardholderRelationship.SPOUSE, null, "1234", LocalDate.now(), LocalDate.now()
        );

        assertThatThrownBy(() -> service.addAddon(account.getId(), req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void closeCardholder_failsOnPrimary() {
        Cardholder primary = new Cardholder();
        primary.setId(UUID.randomUUID());
        primary.setUser(user);
        primary.setAccount(account);
        primary.setRole(CardholderRole.PRIMARY);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByIdAndAccountId(primary.getId(), account.getId())).thenReturn(Optional.of(primary));

        assertThatThrownBy(() -> service.closeCardholder(account.getId(), primary.getId(), LocalDate.now()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Primary cardholder cannot be closed");
    }

    @Test
    void closeCardholder_successOnAddon() {
        Cardholder addon = new Cardholder();
        addon.setId(UUID.randomUUID());
        addon.setUser(user);
        addon.setAccount(account);
        addon.setRole(CardholderRole.ADDON);
        addon.setPersonName("Jane Doe");
        addon.setOpenedOn(LocalDate.of(2026, 1, 1));

        Card openCard = new Card();
        openCard.setId(UUID.randomUUID());
        openCard.setLast4("5678");
        openCard.setIssuedOn(LocalDate.of(2026, 1, 1));
        openCard.setCardholder(addon);
        addon.getCards().add(openCard);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByIdAndAccountId(addon.getId(), account.getId())).thenReturn(Optional.of(addon));
        when(cardholderRepository.save(any(Cardholder.class))).thenAnswer(i -> i.getArgument(0));

        CardholderResponse response = service.closeCardholder(account.getId(), addon.getId(), LocalDate.of(2026, 6, 1));

        assertThat(response.closedOn()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void addCard_failsIfAlreadyHasOpenCard() {
        Cardholder addon = new Cardholder();
        addon.setId(UUID.randomUUID());
        addon.setUser(user);
        addon.setAccount(account);
        addon.setRole(CardholderRole.ADDON);

        Card openCard = new Card();
        openCard.setLast4("5678");
        addon.getCards().add(openCard);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByIdAndAccountId(addon.getId(), account.getId())).thenReturn(Optional.of(addon));

        CreateCardRequest req = new CreateCardRequest("9999", LocalDate.now());

        assertThatThrownBy(() -> service.addCard(account.getId(), addon.getId(), req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already has an active card");
    }

    @Test
    void replaceCard_success_closesOldCardAndSavesNew() {
        Cardholder addon = new Cardholder();
        addon.setId(UUID.randomUUID());
        addon.setUser(user);
        addon.setAccount(account);
        addon.setRole(CardholderRole.ADDON);

        Card oldCard = new Card();
        oldCard.setId(UUID.randomUUID());
        oldCard.setLast4("1111");
        oldCard.setIssuedOn(LocalDate.of(2026, 1, 1));
        oldCard.setCardholder(addon);
        addon.getCards().add(oldCard);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardholderRepository.findByIdAndAccountId(addon.getId(), account.getId())).thenReturn(Optional.of(addon));
        when(cardRepository.findById(oldCard.getId())).thenReturn(Optional.of(oldCard));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(cardRepository.findOpenByAccountIdAndLast4(account.getId(), "2222")).thenReturn(Optional.empty());
        when(cardRepository.save(any(Card.class))).thenAnswer(i -> {
            Card c = i.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });

        ReplaceCardRequest req = new ReplaceCardRequest("2222", LocalDate.of(2026, 5, 1));
        CardholderResponse res = service.replaceCard(account.getId(), addon.getId(), oldCard.getId(), req);

        assertThat(oldCard.getClosedOn()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(res.currentLast4()).isEqualTo("2222");
        assertThat(res.cards()).hasSize(2);
        verify(cardRepository).saveAndFlush(oldCard);
    }
}
