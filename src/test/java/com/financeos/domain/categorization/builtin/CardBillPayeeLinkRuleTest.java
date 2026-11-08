package com.financeos.domain.categorization.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.financeos.gmail.extract.Direction;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The card-bill payee link rule on the bank-alert descriptions of CRED payments (as the bill-payment matcher feeds it: description as counterparty and text). */
class CardBillPayeeLinkRuleTest {

    private static BuiltinCategorizer categorizer;

    @BeforeAll
    static void load() {
        categorizer = new BuiltinCategorizer();
    }

    private static BuiltinMatch debit(String description) {
        return categorizer.categorize(new CategorizeInput(description, Direction.DEBIT, new BigDecimal("3675"), null, null, description, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CRED Club",
            "CRED",
            "Club-payment",
            "CRED Club-payment",
            "P2M-CRED Club-payment on C-UTIB0000114- HEAD OFFICE"})
    void credPaymentDescriptionsFromBankAlertsAreCardBillLinks(String description) {
        BuiltinMatch m = debit(description);
        assertEquals(Step.LINK, m.step(), description + " -> " + m.counterpartyName());
        assertEquals("card_bill", m.categoryKey(), description);
    }

    @Test
    void aCreditOfTheSameTextIsNotACardBillPayeeDebit() {
        BuiltinMatch m = categorizer.categorize(new CategorizeInput("Club-payment", Direction.CREDIT, new BigDecimal("100"), null, null,
                "Club-payment", null, null));
        assertNotEquals("card_bill", m.categoryKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Club membership", "Credit Union fee", "Cricket Club-payments dues Zyxq"})
    void otherClubAndCredLookingTextIsNotACardBillPayee(String description) {
        BuiltinMatch m = debit(description);
        assertNotEquals("card_bill", m.categoryKey(), description);
    }
}
