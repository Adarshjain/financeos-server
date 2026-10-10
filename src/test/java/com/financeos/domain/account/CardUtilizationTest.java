package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Live utilisation: owed = max(0, −balance); limit = account's, else statement's; one decimal. */
class CardUtilizationTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void owedIsTheNegativePartOfTheBalance() {
        assertEquals(bd("48250"), CardUtilization.owed(bd("-48250")));
        assertEquals(BigDecimal.ZERO, CardUtilization.owed(bd("1200")), "a card in credit owes nothing");
        assertEquals(BigDecimal.ZERO, CardUtilization.owed(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, CardUtilization.owed(null));
    }

    @Test
    void limitPrefersAPositiveAccountLimit() {
        assertEquals(bd("200000"), CardUtilization.limit(bd("200000"), bd("100000")));
        assertEquals(bd("100000"), CardUtilization.limit(null, bd("100000")));
        assertEquals(bd("100000"), CardUtilization.limit(BigDecimal.ZERO, bd("100000")), "a zero account limit is unset");
        assertEquals(bd("100000"), CardUtilization.limit(bd("-5"), bd("100000")));
        assertNull(CardUtilization.limit(null, null));
        assertNull(CardUtilization.limit(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void pctIsOwedOverLimitWithOneDecimal() {
        assertEquals(bd("24.1"), CardUtilization.pct(bd("-48250"), bd("200000"), null));
        assertEquals(bd("50.0"), CardUtilization.pct(bd("-50000"), null, bd("100000")));
        assertEquals(bd("150.0"), CardUtilization.pct(bd("-150000"), bd("100000"), null), "over the limit is over 100");
    }

    @Test
    void pctRoundsHalfUp() {
        assertEquals(bd("33.4"), CardUtilization.pct(bd("-33350"), bd("100000"), null));
        assertEquals(bd("33.3"), CardUtilization.pct(bd("-33349"), bd("100000"), null));
    }

    @Test
    void aCardInCreditIsZeroNotTheAbsoluteValue() {
        assertEquals(bd("0.0"), CardUtilization.pct(bd("5000"), bd("100000"), null));
        assertEquals(bd("0.0"), CardUtilization.pct(null, bd("100000"), null));
    }

    @Test
    void pctIsNullWithoutAPositiveLimit() {
        assertNull(CardUtilization.pct(bd("-5000"), null, null));
        assertNull(CardUtilization.pct(bd("-5000"), BigDecimal.ZERO, null));
    }

    @Test
    void accountLimitReadsTheCardDetails() {
        Account card = new Account("Card", AccountType.credit_card);
        assertNull(CardUtilization.accountLimit(card));
        card.setCreditCardDetails(new AccountCreditCardDetails(card, bd("75000"), null));
        assertEquals(bd("75000"), CardUtilization.accountLimit(card));
    }
}
