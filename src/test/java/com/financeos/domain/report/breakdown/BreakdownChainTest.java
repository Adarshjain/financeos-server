package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BreakdownChainTest {

    @Test
    void positiveAmountKeepsTermSignsAndClosesOnTheTotal() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("800"))
                .start("Opening", new BigDecimal("1000"))
                .term("Credits", new BigDecimal("300"))
                .term("Debits", new BigDecimal("-500"))
                .close("Balance", new BigDecimal("800"), "test");

        assertEquals(List.of(
                new BreakdownStep("start", "Opening", null, new BigDecimal("1000"), "currency"),
                new BreakdownStep("add", "Credits", null, new BigDecimal("300"), "currency"),
                new BreakdownStep("subtract", "Debits", null, new BigDecimal("500"), "currency"),
                new BreakdownStep("equals", "Balance", null, new BigDecimal("800"), "currency")), steps);
    }

    @Test
    void negativeAmountFlipsEveryTermSoTheChainClosesOnItsMagnitude() {
        // A card balance of −700: owed 1000 at the statement, 200 spent, 500 paid.
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("-700"))
                .start("Owed", new BigDecimal("-1000"))
                .term("Spends", new BigDecimal("-200"))
                .term("Payments", new BigDecimal("500"))
                .close("Outstanding", new BigDecimal("700"), "test");

        assertEquals(List.of(
                new BreakdownStep("start", "Owed", null, new BigDecimal("1000"), "currency"),
                new BreakdownStep("add", "Spends", null, new BigDecimal("200"), "currency"),
                new BreakdownStep("subtract", "Payments", null, new BigDecimal("500"), "currency"),
                new BreakdownStep("equals", "Outstanding", null, new BigDecimal("700"), "currency")), steps);
    }

    @Test
    void zeroAmountKeepsTheNaturalDirectionAndAZeroTermIsAnAdd() {
        List<BreakdownStep> steps = new BreakdownChain(BigDecimal.ZERO)
                .term("Credits", new BigDecimal("50"))
                .term("Debits", new BigDecimal("-50"))
                .term("Nothing", BigDecimal.ZERO)
                .close("Balance", BigDecimal.ZERO, "test");

        assertEquals(List.of("add", "subtract", "add", "equals"), steps.stream().map(BreakdownStep::op).toList());
        assertEquals(BigDecimal.ZERO, steps.get(2).amount());
    }

    @Test
    void chainWithoutAStartSumsFromZero() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("40"))
                .term("Lent", new BigDecimal("40"))
                .close("They owe you", new BigDecimal("40"), "test");

        assertEquals(List.of("add", "equals"), steps.stream().map(BreakdownStep::op).toList());
    }

    @Test
    void residualAboveTheTermsIsAnExplicitRoundingAdd() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("100.01"))
                .start("Principal", new BigDecimal("100"))
                .close("Outstanding", new BigDecimal("100.01"), "test");

        assertEquals(new BreakdownStep("add", "Rounding difference", null, new BigDecimal("0.01"), "currency"), steps.get(1));
        assertEquals("equals", steps.get(2).op());
    }

    @Test
    void residualBelowTheTermsIsAnExplicitRoundingSubtract() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("99.995"))
                .start("Principal", new BigDecimal("100"))
                .close("Outstanding", new BigDecimal("99.995"), "test");

        assertEquals(new BreakdownStep("subtract", "Rounding difference", null, new BigDecimal("0.005"), "currency"),
                steps.get(1));
    }

    @Test
    void differentScaleOfTheSameAmountIsNoResidual() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("10.00"))
                .start("Opening", new BigDecimal("10"))
                .close("Balance", new BigDecimal("10.00"), "test");

        assertEquals(List.of("start", "equals"), steps.stream().map(BreakdownStep::op).toList());
    }

    @Test
    void infoStepsKeepTheirPlaceAndTakeNoPartInTheSum() {
        List<BreakdownStep> steps = new BreakdownChain(new BigDecimal("5"))
                .start("Opening", new BigDecimal("5"))
                .info("Foreclosed on 01/01/2026", "detail", new BigDecimal("999"), "currency")
                .close("Balance", new BigDecimal("5"), "test");

        assertEquals(List.of(
                new BreakdownStep("start", "Opening", null, new BigDecimal("5"), "currency"),
                new BreakdownStep("info", "Foreclosed on 01/01/2026", "detail", new BigDecimal("999"), "currency"),
                new BreakdownStep("equals", "Balance", null, new BigDecimal("5"), "currency")), steps);
    }
}
