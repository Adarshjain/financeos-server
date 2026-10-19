package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Same-last4 ambiguity is broken by the bank named in the email text, and only then. */
class AccountResolverBankTiebreakTest {

    private AccountRepository accountRepository;
    private CardRepository cardRepository;
    private AccountIdentifierRepository identifierRepository;
    private AccountResolver resolver;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(CardRepository.class);
        identifierRepository = mock(AccountIdentifierRepository.class);
        resolver = new AccountResolver(accountRepository, cardRepository, identifierRepository);
        when(identifierRepository.findAll()).thenReturn(List.of());
    }

    private Account card(String name, String bank) {
        Account a = new Account(name, AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setBank(bank);
        return a;
    }

    private Card cardOf(Account a, String last4) {
        Card c = new Card();
        c.setId(UUID.randomUUID());
        c.setAccount(a);
        c.setLast4(last4);
        return c;
    }

    private void given(Account a, Account b, String last4) {
        when(accountRepository.findAll()).thenReturn(List.of(a, b));
        when(cardRepository.findAll()).thenReturn(List.of(cardOf(a, last4), cardOf(b, last4)));
    }

    private Optional<AccountResolver.ResolvedCard> resolve(String last4, String... texts) {
        return resolver.resolve(last4, List.of(texts));
    }

    @Test
    void twoCandidatesDifferentBanks_theOneNamedInTheEmailWins() {
        Account hdfc = card("HDFC card", "hdfc");
        Account icici = card("ICICI card", "icici");
        given(hdfc, icici, "1111");

        assertThat(resolve("1111", "Alert", "Spent on your ICICI Bank Credit Card XX1111"))
                .map(r -> r.account()).contains(icici);
        assertThat(resolve("1111", "HDFC Bank alert", "card ending 1111"))
                .map(r -> r.account()).contains(hdfc);
    }

    @Test
    void bothCandidatesSameBank_staysUnresolved() {
        given(card("A", "hdfc"), card("B", "hdfc"), "1111");
        assertThat(resolve("1111", "HDFC Bank alert")).isEmpty();
    }

    @Test
    void noBankSetOnCandidates_staysUnresolved() {
        given(card("A", null), card("B", null), "1111");
        assertThat(resolve("1111", "HDFC Bank alert")).isEmpty();
    }

    @Test
    void onlyOneCandidateHasABank_andItIsNamed_resolvesToIt() {
        Account hdfc = card("A", "hdfc");
        given(hdfc, card("B", null), "1111");
        assertThat(resolve("1111", "HDFC Bank alert")).map(r -> r.account()).contains(hdfc);
    }

    @Test
    void emailNamesNeitherBank_staysUnresolved() {
        given(card("A", "hdfc"), card("B", "icici"), "1111");
        assertThat(resolve("1111", "Rs 500 spent at Amazon")).isEmpty();
        assertThat(resolve("1111", "Axis Bank alert")).isEmpty();
    }

    @Test
    void noEmailText_staysUnresolved() {
        given(card("A", "hdfc"), card("B", "icici"), "1111");
        assertThat(resolver.resolve("1111")).isEmpty();
        assertThat(resolver.resolve("1111", List.of())).isEmpty();
    }

    @Test
    void southIndianBankTextDoesNotMatchIndianBank() {
        given(card("A", "indian_bank"), card("B", "hdfc"), "1111");
        assertThat(resolve("1111", "South Indian Bank alert")).isEmpty();
        assertThat(resolve("1111", "Indian Overseas Bank alert")).isEmpty();
        Account indian = card("D", "indian_bank");
        given(indian, card("E", "hdfc"), "2222");
        assertThat(resolve("2222", "Indian Bank alert")).map(r -> r.account()).contains(indian);
    }

    @Test
    void sbiMatchesAsWholeWordOnly() {
        Account sbi = card("A", "sbi");
        given(sbi, card("B", "hdfc"), "1111");
        assertThat(resolve("1111", "SBIN0001234 credited")).isEmpty();
        assertThat(resolve("1111", "SBI alert")).map(r -> r.account()).contains(sbi);
    }

    @Test
    void bankAccountCandidatesAreTieBrokenToo() {
        Account a = new Account("Savings A", AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setBank("axis");
        a.setBankDetails(new com.financeos.domain.account.AccountBankDetails(a, null, "3333", null));
        Account b = new Account("Savings B", AccountType.bank_account);
        b.setId(UUID.randomUUID());
        b.setBank("federal");
        b.setBankDetails(new com.financeos.domain.account.AccountBankDetails(b, null, "3333", null));
        when(accountRepository.findAll()).thenReturn(List.of(a, b));
        when(cardRepository.findAll()).thenReturn(List.of());

        assertThat(resolve("3333", "Federal Bank alert")).map(r -> r.account()).contains(b);
    }

    @Test
    void noAmbiguity_unchangedAndEmailIgnored() {
        Account only = card("A", "hdfc");
        when(accountRepository.findAll()).thenReturn(List.of(only));
        when(cardRepository.findAll()).thenReturn(List.of(cardOf(only, "1111")));
        // The email names another bank: the single match still stands.
        assertThat(resolve("1111", "ICICI Bank alert")).map(r -> r.account()).contains(only);
    }

    @Test
    void aliasStillBeatsTheTieBreak() {
        Account hdfc = card("HDFC card", "hdfc");
        Account icici = card("ICICI card", "icici");
        given(hdfc, icici, "1111");
        AccountIdentifier alias = new AccountIdentifier();
        alias.setAccount(hdfc);
        alias.setValue("1111");
        when(identifierRepository.findAll()).thenReturn(List.of(alias));

        assertThat(resolve("1111", "ICICI Bank alert")).map(r -> r.account()).contains(hdfc);
    }
}
