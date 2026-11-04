package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.core.time.AppTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountClosePromptTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static Instant daysAgo(int days) {
        return TODAY.minusDays(days).atTime(12, 0).atZone(IST).toInstant();
    }

    /** A bank account that qualifies on every rule. */
    private static Account quietAccount() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(AccountType.bank_account);
        a.setCalculatedBalance(new BigDecimal("0.00"));
        a.setCreatedAt(daysAgo(200));
        a.setLastTransactionDate(TODAY.minusDays(120));
        return a;
    }

    @Test
    void suggestsAnEmptyOpenAccountWithNoActivityFor90Days() {
        assertTrue(AccountClosePrompt.suggest(quietAccount(), TODAY));
    }

    @Test
    void suggestsAnEmptyAccountThatNeverHadATransaction() {
        Account a = quietAccount();
        a.setLastTransactionDate(null);

        assertTrue(AccountClosePrompt.suggest(a, TODAY));
    }

    @Test
    void neverForAClosedAccountOrABroker() {
        Account closed = quietAccount();
        closed.setClosedOn(TODAY.minusDays(1));
        assertFalse(AccountClosePrompt.suggest(closed, TODAY));

        Account broker = quietAccount();
        broker.setType(AccountType.broker);
        assertFalse(AccountClosePrompt.suggest(broker, TODAY));
    }

    @Test
    void neverWhenTheBalanceIsNotExactlyZero() {
        Account owes = quietAccount();
        owes.setCalculatedBalance(new BigDecimal("-0.01"));
        assertFalse(AccountClosePrompt.suggest(owes, TODAY));

        Account holds = quietAccount();
        holds.setCalculatedBalance(new BigDecimal("1200"));
        assertFalse(AccountClosePrompt.suggest(holds, TODAY));

        Account unknown = quietAccount();
        unknown.setCalculatedBalance(null);
        assertFalse(AccountClosePrompt.suggest(unknown, TODAY));
    }

    @Test
    void aCreditCardWithAnOpenBillOwesSoItsBalanceIsNotZero() {
        Account card = quietAccount();
        card.setType(AccountType.credit_card);
        card.setCalculatedBalance(new BigDecimal("-12340"));
        assertFalse(AccountClosePrompt.suggest(card, TODAY));

        card.setCalculatedBalance(BigDecimal.ZERO);
        assertTrue(AccountClosePrompt.suggest(card, TODAY));
    }

    @Test
    void neverForAnAccountCreatedInTheLast90Days() {
        Account young = quietAccount();
        young.setCreatedAt(daysAgo(89));
        assertFalse(AccountClosePrompt.suggest(young, TODAY));

        young.setCreatedAt(daysAgo(90));
        assertTrue(AccountClosePrompt.suggest(young, TODAY));
    }

    @Test
    void neverWithATransactionInTheLast90Days() {
        Account active = quietAccount();
        active.setLastTransactionDate(TODAY.minusDays(89));
        assertFalse(AccountClosePrompt.suggest(active, TODAY));

        active.setLastTransactionDate(TODAY.minusDays(90));
        assertTrue(AccountClosePrompt.suggest(active, TODAY));
    }

    @Test
    void aDismissalRestsThePromptFor90Days() {
        Account a = quietAccount();
        a.setClosePromptDismissedAt(daysAgo(10));
        assertFalse(AccountClosePrompt.suggest(a, TODAY));

        a.setClosePromptDismissedAt(daysAgo(89));
        assertFalse(AccountClosePrompt.suggest(a, TODAY));

        a.setClosePromptDismissedAt(daysAgo(90));
        assertTrue(AccountClosePrompt.suggest(a, TODAY));
    }
}
