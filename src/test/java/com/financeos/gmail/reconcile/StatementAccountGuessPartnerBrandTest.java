package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A Fi / Jupiter statement is pinned to the partner-bank account it belongs to, bank account vs card by the mail's kind. */
class StatementAccountGuessPartnerBrandTest {

    private final UUID userId = UUID.randomUUID();
    private AccountRepository accountRepository;
    private CardRepository cardRepository;
    private StatementAccountGuess guess;
    private Account federal;
    private Account jupiterCard;
    private Account hdfc;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardRepository = mock(CardRepository.class);
        guess = new StatementAccountGuess(accountRepository, cardRepository, mock(AccountIdentifierRepository.class));
        federal = account("Fi Money", "federal", AccountType.bank_account);
        federal.setBankDetails(new AccountBankDetails(federal, BigDecimal.ZERO, "3974"));
        jupiterCard = account("Jupiter Edge+", "csb", AccountType.credit_card);
        Card c = new Card();
        c.setLast4("2183");
        when(cardRepository.findByAccountId(jupiterCard.getId())).thenReturn(List.of(c));
        hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(federal, jupiterCard, hdfc));
    }

    private Account account(String name, String bank, AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setBank(bank);
        a.setType(type);
        return a;
    }

    private static GmailMessage mail(String from, String subject, String body) {
        return new GmailMessage("m1", Instant.parse("2026-06-05T04:00:00Z"), from, subject, "", body, null,
                List.of(new GmailAttachment("a1", "statement.pdf", "application/pdf", null)));
    }

    @Test
    void fiMoneyStatementPicksTheFederalBankAccount() {
        var g = guess.find(userId, mail("no-reply@fi.money", "Your Fi account statement", "Account ending 3974"));

        assertThat(g).isPresent();
        assertThat(g.get().account()).isSameAs(federal);
    }

    @Test
    void jupiterMoneyCardStatementPicksTheCsbCardNotTheFederalAccount() {
        var g = guess.find(userId, mail("statements@jupiter.money", "Your Jupiter Edge+ credit card statement", "Card ending 2183"));

        assertThat(g).isPresent();
        assertThat(g.get().account()).isSameAs(jupiterCard);
    }

    @Test
    void jupiterStatementDoesNotPickAnUnrelatedBank() {
        assertThat(guess.find(userId, mail("statements@jupiter.money", "Your account statement", "Account ending 9999"))).isEmpty();
    }

    @Test
    void exactBankMatchBeatsPartnerMatch() {
        Account csbAccount = account("CSB Savings", "csb", AccountType.bank_account);
        csbAccount.setBankDetails(new AccountBankDetails(csbAccount, BigDecimal.ZERO, "3974"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(federal, csbAccount));

        var g = guess.find(userId, mail("alerts@csb.bank.in", "Your account statement", "Account ending 3974"));

        assertThat(g).isPresent();
        assertThat(g.get().account()).isSameAs(csbAccount);
    }
}
