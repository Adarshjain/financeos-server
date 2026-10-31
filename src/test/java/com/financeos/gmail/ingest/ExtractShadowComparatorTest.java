package com.financeos.gmail.ingest;

import com.financeos.gmail.extract.Direction;
import com.financeos.gmail.extract.ExtractedTransaction;
import com.financeos.gmail.extract.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractShadowComparatorTest {

    @Test
    void counterpartyIsLenient() {
        assertThat(ExtractShadowComparator.counterpartyEquals("BLUE LEAF CAFE", "Blue Leaf Cafe")).isTrue();
        assertThat(ExtractShadowComparator.counterpartyEquals("Blue Leaf", "Payment to Blue Leaf Cafe, Pune")).isTrue();
        assertThat(ExtractShadowComparator.counterpartyEquals("Blue Leaf Cafe, Pune", "blue leaf")).isTrue();
        assertThat(ExtractShadowComparator.counterpartyEquals("shop@upi", "Shop@UPI")).isTrue();
    }

    @Test
    void counterpartyDifferenceAndEmptiness() {
        assertThat(ExtractShadowComparator.counterpartyEquals("Blue Leaf", "Green Door")).isFalse();
        assertThat(ExtractShadowComparator.counterpartyEquals(null, "Green Door")).isFalse();
        assertThat(ExtractShadowComparator.counterpartyEquals("Green Door", "  ")).isFalse();
        assertThat(ExtractShadowComparator.counterpartyEquals(null, null)).isTrue();
        assertThat(ExtractShadowComparator.counterpartyEquals("--", "")).isTrue();
    }

    private static ExtractedTransaction engine(String last4) {
        return new ExtractedTransaction(new java.math.BigDecimal("10.0"), "INR", Direction.DEBIT,
                java.time.LocalDate.of(2026, 10, 1), "Shop", last4, Tier.GENERIC, null, 0.6);
    }

    private static com.financeos.gmail.ingest.gemini.GeminiExtractionResult ai(String amount, String last4) {
        return new com.financeos.gmail.ingest.gemini.GeminiExtractionResult(true, new java.math.BigDecimal(amount), "inr",
                "debit", java.time.LocalDate.of(2026, 10, 1), "Shop", last4, 0.9, true, null);
    }

    @Test
    void amountIsNumericAndLast4UsesTheLastFourDigits() {
        assertThat(ExtractShadowComparator.differingFields(engine("4821"), ai("10.00", "xx 4821"))).isEmpty();
        assertThat(ExtractShadowComparator.differingFields(engine("4821"), ai("10", "00004821"))).isEmpty();
        assertThat(ExtractShadowComparator.differingFields(engine(null), ai("10", null))).isEmpty();
        assertThat(ExtractShadowComparator.differingFields(engine("4821"), ai("10", null))).containsExactly("last4");
        assertThat(ExtractShadowComparator.differingFields(engine(null), ai("11", "9"))).containsExactly("amount", "last4");
    }
}
