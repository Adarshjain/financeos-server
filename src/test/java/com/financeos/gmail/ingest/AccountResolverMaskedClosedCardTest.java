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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A statement names the account by its masked full number ("######XXXXXX5554"). Open cards already matched it by their
 * trailing digits; a closed card (Eterna, closed 2026-08-31) matched only an exact 4-digit number, so the statement parked as unresolved.
 */
class AccountResolverMaskedClosedCardTest {

    private AccountRepository accountRepository;
    private CardRepository cardRepository;
    private AccountResolver resolver;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(CardRepository.class);
        resolver = new AccountResolver(accountRepository, cardRepository, mock(AccountIdentifierRepository.class));
    }

    private Account account(String name) {
        Account a = new Account(name, AccountType.credit_card);
        a.setId(UUID.randomUUID());
        return a;
    }

    private Card card(Account account, String last4, LocalDate closedOn) {
        Card c = new Card();
        c.setId(UUID.randomUUID());
        c.setAccount(account);
        c.setLast4(last4);
        c.setClosedOn(closedOn);
        return c;
    }

    @Test
    void maskedStatementNumber_resolvesTheClosedCard() {
        Account eterna = account("Eterna");
        Card closed = card(eterna, "5554", LocalDate.of(2026, 8, 31));
        when(accountRepository.findAll()).thenReturn(List.of(eterna));
        when(cardRepository.findAll()).thenReturn(List.of(closed));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("######XXXXXX5554");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isSameAs(eterna);
        assertThat(result.get().card()).isSameAs(closed);
    }

    @Test
    void maskedNumber_withSeparators_stillMatchesTheTrailingDigits() {
        Account eterna = account("Eterna");
        when(accountRepository.findAll()).thenReturn(List.of(eterna));
        when(cardRepository.findAll()).thenReturn(List.of(card(eterna, "5554", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("XXXX XXXX XXXX 5554")).isPresent();
    }

    @Test
    void maskedNumber_endingInOtherDigits_doesNotMatchAClosedCard() {
        Account eterna = account("Eterna");
        when(accountRepository.findAll()).thenReturn(List.of(eterna));
        when(cardRepository.findAll()).thenReturn(List.of(card(eterna, "5554", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("######XXXXXX1554")).isEmpty();
    }

    @Test
    void twoClosedCardsOfOneAccount_resolveThatAccount() {
        Account eterna = account("Eterna");
        when(accountRepository.findAll()).thenReturn(List.of(eterna));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(eterna, "5554", LocalDate.of(2026, 3, 1)), card(eterna, "5554", LocalDate.of(2026, 8, 31))));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("######XXXXXX5554");

        assertThat(result).isPresent();
        assertThat(result.get().account()).isSameAs(eterna);
    }

    @Test
    void closedCardsOfTwoAccounts_stayAmbiguous() {
        Account a = account("Eterna");
        Account b = account("Other");
        when(accountRepository.findAll()).thenReturn(List.of(a, b));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(a, "5554", LocalDate.of(2026, 3, 1)), card(b, "5554", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("######XXXXXX5554")).isEmpty();
    }

    @Test
    void anOpenCardStillWinsOverAClosedOneWithTheSameDigits() {
        Account old = account("Old");
        Account current = account("Current");
        Card open = card(current, "5554", null);
        when(accountRepository.findAll()).thenReturn(List.of(old, current));
        when(cardRepository.findAll()).thenReturn(List.of(card(old, "5554", LocalDate.of(2026, 8, 31)), open));

        Optional<AccountResolver.ResolvedCard> result = resolver.resolve("######XXXXXX5554");

        assertThat(result).isPresent();
        assertThat(result.get().card()).isSameAs(open);
    }

    private Account bankAccount(String name, String bank) {
        Account a = account(name);
        a.setBank(bank);
        return a;
    }

    @Test
    void maskedNumber_fromAnotherBank_doesNotMatchAClosedCardOfTheFirstBank() {
        Account hdfc = bankAccount("HDFC old", "hdfc");
        when(accountRepository.findAll()).thenReturn(List.of(hdfc));
        when(cardRepository.findAll()).thenReturn(List.of(card(hdfc, "1234", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("######XXXXXX1234", List.of("Your ICICI Bank statement"), "alerts@icicibank.com")).isEmpty();
    }

    @Test
    void maskedNumber_fromTheSameBank_matchesTheClosedCard() {
        Account hdfc = bankAccount("HDFC old", "hdfc");
        when(accountRepository.findAll()).thenReturn(List.of(hdfc));
        when(cardRepository.findAll()).thenReturn(List.of(card(hdfc, "1234", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("######XXXXXX1234", List.of(), "alerts@hdfcbank.net")).isPresent();
    }

    @Test
    void maskedNumber_withTheBankNamedInTheText_matchesTheClosedCard() {
        Account hdfc = bankAccount("HDFC old", "hdfc");
        when(accountRepository.findAll()).thenReturn(List.of(hdfc));
        when(cardRepository.findAll()).thenReturn(List.of(card(hdfc, "1234", LocalDate.of(2026, 8, 31))));

        assertThat(resolver.resolve("######XXXXXX1234", List.of("Your HDFC Bank statement"), "noreply@unknown.example")).isPresent();
    }
}
