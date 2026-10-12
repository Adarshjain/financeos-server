package com.financeos.domain.account;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BalanceMath#cutoff}: the last day an anchor statement bills, and how
 * {@link BalanceMath#apply} records it on the account.
 */
class BalanceMathCutoffTest {

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 18);

    @Test
    void noAnchorHasNoCutoff() {
        assertNull(BalanceMath.cutoff(null, null));
        assertNull(BalanceMath.cutoff(null, PERIOD_END.minusDays(1)));
    }

    @Test
    void aStatementWithoutLinesBillsThroughItsPeriodEnd() {
        assertEquals(PERIOD_END, BalanceMath.cutoff(PERIOD_END, null));
    }

    @Test
    void aStatementWhoseLastLineComesBeforeThePeriodEndBillsThroughThatLine() {
        assertEquals(PERIOD_END.minusDays(1), BalanceMath.cutoff(PERIOD_END, PERIOD_END.minusDays(1)));
        assertEquals(PERIOD_END.minusDays(9), BalanceMath.cutoff(PERIOD_END, PERIOD_END.minusDays(9)));
    }

    @Test
    void aStatementListingItsPeriodEndBillsThroughThePeriodEnd() {
        assertEquals(PERIOD_END, BalanceMath.cutoff(PERIOD_END, PERIOD_END));
    }

    @Test
    void aLineDatedAfterThePeriodEndNeverMovesTheCutoffLater() {
        assertEquals(PERIOD_END, BalanceMath.cutoff(PERIOD_END, PERIOD_END.plusDays(3)));
    }

    @Test
    void applyRecordsTheCutoffBesideTheAnchorDate() {
        Account card = new Account("Card", AccountType.credit_card);

        BalanceMath.apply(card, PERIOD_END, PERIOD_END.minusDays(1), new BigDecimal("56843.16"),
                new BigDecimal("-70000"), new BigDecimal("-10042.34"));

        assertTrue(card.getBalanceAnchored());
        assertEquals(PERIOD_END, card.getAnchorDate());
        assertEquals(PERIOD_END.minusDays(1), card.getAnchorCutoff());
        assertEquals(0, new BigDecimal("-66885.50").compareTo(card.getCalculatedBalance()));
    }

    @Test
    void applyWithoutACutoffUsesTheAnchorDate() {
        Account bank = new Account("Bank", AccountType.bank_account);

        BalanceMath.apply(bank, PERIOD_END, new BigDecimal("500"), new BigDecimal("300"), new BigDecimal("50"));

        assertEquals(PERIOD_END, bank.getAnchorDate());
        assertEquals(PERIOD_END, bank.getAnchorCutoff());
        assertEquals(0, new BigDecimal("550").compareTo(bank.getCalculatedBalance()));

        BalanceMath.apply(bank, PERIOD_END, null, new BigDecimal("500"), new BigDecimal("300"), new BigDecimal("50"));
        assertEquals(PERIOD_END, bank.getAnchorCutoff());
    }

    @Test
    void anUnanchoredAccountClearsTheCutoff() {
        Account card = new Account("Card", AccountType.credit_card);
        BalanceMath.apply(card, PERIOD_END, PERIOD_END.minusDays(1), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(PERIOD_END.minusDays(1), card.getAnchorCutoff());

        BalanceMath.apply(card, null, null, null, new BigDecimal("-25"), null);

        assertFalse(card.getBalanceAnchored());
        assertNull(card.getAnchorDate());
        assertNull(card.getAnchorCutoff());
        assertEquals(0, new BigDecimal("-25").compareTo(card.getCalculatedBalance()));
    }
}
