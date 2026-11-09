package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** Same-last4 tie-break by sender: a partner brand's mail covers its partner banks, an exact bank match wins. */
class AccountResolverPartnerBrandTest {

    private AccountRepository accountRepository;
    private CardRepository cardRepository;
    private AccountResolver resolver;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(CardRepository.class);
        AccountIdentifierRepository identifierRepository = mock(AccountIdentifierRepository.class);
        resolver = new AccountResolver(accountRepository, cardRepository, identifierRepository);
        when(identifierRepository.findAll()).thenReturn(List.of());
    }

    private Account card(String bank) {
        Account a = new Account(bank + " card", AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setBank(bank);
        return a;
    }

    private Card cardOf(Account a) {
        Card c = new Card();
        c.setId(UUID.randomUUID());
        c.setAccount(a);
        c.setLast4("2183");
        return c;
    }

    private void given(Account... accounts) {
        when(accountRepository.findAll()).thenReturn(List.of(accounts));
        when(cardRepository.findAll()).thenReturn(java.util.Arrays.stream(accounts).map(this::cardOf).toList());
    }

    @Test
    void brandSenderPicksTheOnePartnerBankAccount() {
        Account csb = card("csb");
        Account hdfc = card("hdfc");
        given(csb, hdfc);

        assertThat(resolver.resolve("2183", List.of(), "no-reply@jupiter.money")).map(r -> r.account()).contains(csb);
    }

    @Test
    void exactBankMatchBeatsPartnerMatch() {
        Account federal = card("federal");
        Account csb = card("csb");
        given(federal, csb);

        // csb.bank.in is exactly csb: federal is not even a partner candidate then
        assertThat(resolver.resolve("2183", List.of(), "alerts@csb.bank.in")).map(r -> r.account()).contains(csb);
    }

    @Test
    void brandSenderWithTwoPartnerAccountsStaysAmbiguous() {
        given(card("federal"), card("csb"));

        assertThat(resolver.resolve("2183", List.of(), "no-reply@jupiter.money")).isEmpty();
    }
}
