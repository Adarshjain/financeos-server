package com.financeos.gmail.ingest;

import com.financeos.domain.account.*;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountResolverAliasTest {

    private AccountRepository accountRepository;
    private CardRepository cardRepository;
    private AccountIdentifierRepository accountIdentifierRepository;
    private AccountResolver resolver;

    private User testUser;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(CardRepository.class);
        accountIdentifierRepository = mock(AccountIdentifierRepository.class);
        resolver = new AccountResolver(accountRepository, cardRepository, accountIdentifierRepository);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
    }

    @Test
    void pass0_aliasExactHitWinsOverCollidingCardOnAnotherAccount() {
        Account aliasAccount = new Account("HDFC Savings", AccountType.bank_account);
        aliasAccount.setId(UUID.randomUUID());
        AccountIdentifier alias = new AccountIdentifier(
                UUID.randomUUID(), testUser, aliasAccount, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        Account cardAccount = new Account("ICICI Credit", AccountType.credit_card);
        cardAccount.setId(UUID.randomUUID());
        Card collidingCard = new Card();
        collidingCard.setId(UUID.randomUUID());
        collidingCard.setAccount(cardAccount);
        collidingCard.setLast4("1234");

        when(accountIdentifierRepository.findAll()).thenReturn(List.of(alias));
        when(accountRepository.findAll()).thenReturn(List.of(aliasAccount, cardAccount));
        when(cardRepository.findAll()).thenReturn(List.of(collidingCard));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("1234");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(aliasAccount);
        assertThat(result.get().card()).isNull();
    }

    @Test
    void pass0_twoAliasHits_returnsEmpty() {
        Account acc1 = new Account("HDFC Savings", AccountType.bank_account);
        acc1.setId(UUID.randomUUID());
        AccountIdentifier alias1 = new AccountIdentifier(
                UUID.randomUUID(), testUser, acc1, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        Account acc2 = new Account("SBI Savings", AccountType.bank_account);
        acc2.setId(UUID.randomUUID());
        AccountIdentifier alias2 = new AccountIdentifier(
                UUID.randomUUID(), testUser, acc2, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        when(accountIdentifierRepository.findAll()).thenReturn(List.of(alias1, alias2));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("1234");

        assertThat(result).isEmpty();
    }

    @Test
    void pass0_noAliasHits_fallsThroughToPass1() {
        Account bankAccount = new Account("HDFC Savings", AccountType.bank_account);
        bankAccount.setId(UUID.randomUUID());
        bankAccount.setBankDetails(new AccountBankDetails(bankAccount, null, "5678"));

        when(accountIdentifierRepository.findAll()).thenReturn(List.of());
        when(accountRepository.findAll()).thenReturn(List.of(bankAccount));
        when(cardRepository.findAll()).thenReturn(List.of());

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("5678");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(bankAccount);
    }

    @Test
    void pass2_dedup_sameAccountMatchingViaBothAliasAndBankDetailsFuzzily_resolvesSingleCandidate() {
        Account bankAccount = new Account("HDFC Savings", AccountType.bank_account);
        bankAccount.setId(UUID.randomUUID());
        bankAccount.setBankDetails(new AccountBankDetails(bankAccount, null, "123456"));

        AccountIdentifier alias = new AccountIdentifier(
                UUID.randomUUID(), testUser, bankAccount, "99123456", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        when(accountIdentifierRepository.findAll()).thenReturn(List.of(alias));
        when(accountRepository.findAll()).thenReturn(List.of(bankAccount));
        when(cardRepository.findAll()).thenReturn(List.of());

        // "3456" matches both "123456" and "99123456" fuzzily
        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("3456");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(bankAccount);
    }

    @Test
    void pass2_dedup_preferringCardWhenMatchingViaAliasAndCard() {
        Account cardAccount = new Account("ICICI Credit", AccountType.credit_card);
        cardAccount.setId(UUID.randomUUID());

        Card card = new Card();
        card.setId(UUID.randomUUID());
        card.setAccount(cardAccount);
        card.setLast4("991234");

        AccountIdentifier alias = new AccountIdentifier(
                UUID.randomUUID(), testUser, cardAccount, "881234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        when(accountIdentifierRepository.findAll()).thenReturn(List.of(alias));
        when(accountRepository.findAll()).thenReturn(List.of(cardAccount));
        when(cardRepository.findAll()).thenReturn(List.of(card));

        // "1234" matches both card and alias on same account fuzzily
        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("1234");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isEqualTo(cardAccount);
        assertThat(result.get().card()).isEqualTo(card);
    }
}
