package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.AccountCard;
import com.financeos.domain.account.card.AccountCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class AccountResolverCardTest {

    private AccountRepository accountRepository;
    private AccountCardRepository cardRepository;
    private AccountResolver resolver;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(AccountCardRepository.class);
        resolver = new AccountResolver(accountRepository, cardRepository);
    }

    @Test
    void pass1_exactMatch_resolvesAccountAndCard() {
        Account account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        AccountCard addonCard = new AccountCard();
        addonCard.setId(UUID.randomUUID());
        addonCard.setAccount(account);
        addonCard.setLast4("5678");

        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(cardRepository.findAll()).thenReturn(List.of(addonCard));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("5678");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(account);
        assertThat(result.get().card()).isEqualTo(addonCard);
    }

    @Test
    void pass1_ambiguousAcrossMultipleAccounts_returnsEmpty() {
        Account acc1 = new Account("Infinia", AccountType.credit_card);
        acc1.setId(UUID.randomUUID());
        AccountCard card1 = new AccountCard();
        card1.setId(UUID.randomUUID());
        card1.setAccount(acc1);
        card1.setLast4("1111");

        Account acc2 = new Account("Regalia", AccountType.credit_card);
        acc2.setId(UUID.randomUUID());
        AccountCard card2 = new AccountCard();
        card2.setId(UUID.randomUUID());
        card2.setAccount(acc2);
        card2.setLast4("1111");

        when(accountRepository.findAll()).thenReturn(List.of(acc1, acc2));
        when(cardRepository.findAll()).thenReturn(List.of(card1, card2));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("1111");

        assertThat(result).isEmpty();
    }

    @Test
    void pass2_fallbackToTrailingDigits() {
        Account account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        AccountCard card = new AccountCard();
        card.setId(UUID.randomUUID());
        card.setAccount(account);
        card.setLast4("3456");

        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(cardRepository.findAll()).thenReturn(List.of(card));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("XX3456");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(account);
        assertThat(result.get().card()).isEqualTo(card);
    }

    @Test
    void pass3_closedCardMatchesWhenNoOpenMatches() {
        Account account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        AccountCard closedCard = new AccountCard();
        closedCard.setId(UUID.randomUUID());
        closedCard.setAccount(account);
        closedCard.setLast4("9999");
        closedCard.setClosedOn(LocalDate.of(2026, 1, 1));

        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(cardRepository.findAll()).thenReturn(List.of(closedCard));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("9999");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(account);
        assertThat(result.get().card()).isEqualTo(closedCard);
    }
}
