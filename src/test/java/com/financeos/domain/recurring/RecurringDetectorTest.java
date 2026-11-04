package com.financeos.domain.recurring;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecurringDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final UUID ACCOUNT = UUID.randomUUID();

    private static RecurringDetector.Payment pay(LocalDate date, String amount) {
        return pay(date, amount, "ACME CLUB", null);
    }

    private static RecurringDetector.Payment pay(LocalDate date, String amount, String description, String category) {
        return new RecurringDetector.Payment(UUID.randomUUID(), date, new BigDecimal(amount), ACCOUNT, description, category);
    }

    private static List<RecurringDetector.Payment> monthly(String amount, int count, LocalDate last) {
        List<RecurringDetector.Payment> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            out.add(pay(last.minusMonths(i), amount));
        }
        return out;
    }

    private static Optional<RecurringDetector.Series> detect(List<RecurringDetector.Payment> payments) {
        return RecurringDetector.detect(payments, TODAY, "ACME CLUB");
    }

    @Test
    void threeMonthlyPaymentsMakeASeries() {
        RecurringDetector.Series s = detect(monthly("499", 3, TODAY.minusDays(10))).orElseThrow();
        assertEquals(RecurringPeriod.MONTHLY, s.period());
        assertEquals(3, s.payments().size());
        assertEquals(0, new BigDecimal("499").compareTo(s.amount()));
        assertEquals(RecurringKind.OTHER, s.kind());
    }

    @Test
    void twoPaymentsAreNotASeries() {
        assertTrue(detect(monthly("499", 2, TODAY.minusDays(10))).isEmpty());
    }

    @Test
    void irregularGapsAreNotASeries() {
        List<RecurringDetector.Payment> p = List.of(
                pay(TODAY.minusDays(80), "300"), pay(TODAY.minusDays(60), "300"), pay(TODAY.minusDays(10), "300"));
        assertTrue(detect(p).isEmpty());
    }

    @Test
    void anAmountSpreadAbove15PercentBreaksTheSeries() {
        List<RecurringDetector.Payment> p = List.of(
                pay(TODAY.minusDays(70), "100"), pay(TODAY.minusDays(40), "100"), pay(TODAY.minusDays(10), "140"));
        assertTrue(detect(p).isEmpty());
    }

    @Test
    void amountsWithin15PercentOfTheMedianAreASeries() {
        List<RecurringDetector.Payment> p = List.of(
                pay(TODAY.minusDays(70), "100"), pay(TODAY.minusDays(40), "110"), pay(TODAY.minusDays(10), "105"));
        RecurringDetector.Series s = detect(p).orElseThrow();
        assertEquals(0, new BigDecimal("105").compareTo(s.amount()));
    }

    @Test
    void aStaleLastPaymentIsNotASeries() {
        // Monthly: the last payment may be at most 45 days old.
        assertTrue(detect(monthly("499", 3, TODAY.minusDays(60))).isEmpty());
        assertTrue(detect(monthly("499", 3, TODAY.minusDays(44))).isPresent());
    }

    @Test
    void weeklyQuarterlyAndYearlyAreRecognised() {
        List<RecurringDetector.Payment> weekly = List.of(
                pay(TODAY.minusDays(23), "50"), pay(TODAY.minusDays(16), "50"), pay(TODAY.minusDays(9), "50"));
        assertEquals(RecurringPeriod.WEEKLY, detect(weekly).orElseThrow().period());

        List<RecurringDetector.Payment> quarterly = List.of(
                pay(TODAY.minusDays(200), "900"), pay(TODAY.minusDays(110), "900"), pay(TODAY.minusDays(20), "900"));
        assertEquals(RecurringPeriod.QUARTERLY, detect(quarterly).orElseThrow().period());

        List<RecurringDetector.Payment> yearly = List.of(
                pay(TODAY.minusDays(800), "2400"), pay(TODAY.minusDays(435), "2400"), pay(TODAY.minusDays(70), "2400"));
        assertEquals(RecurringPeriod.YEARLY, detect(yearly).orElseThrow().period());
    }

    @Test
    void theLatestRunWinsOverOlderIrregularHistory() {
        List<RecurringDetector.Payment> p = new ArrayList<>();
        p.add(pay(TODAY.minusDays(200), "100"));
        p.add(pay(TODAY.minusDays(150), "100"));
        p.addAll(monthly("100", 3, TODAY.minusDays(5)));
        assertEquals(3, detect(p).orElseThrow().payments().size());
    }

    @Test
    void kindIsEmiForLoanLookingPaymentsAndCategories() {
        assertEquals(RecurringKind.EMI, kindOf("BAJAJ FINANCE EMI 123456", null));
        assertEquals(RecurringKind.EMI, kindOf("ACH D- ACME HOME LOAN", null));
        assertEquals(RecurringKind.EMI, kindOf("NACH DEBIT ZORBLAX FINSERV", null));
        assertEquals(RecurringKind.EMI, kindOf("ZORBLAX 998877", "emi.car_loan"));
    }

    @Test
    void kindIsSubscriptionForSubscriptionCategoriesAndKnownServices() {
        assertEquals(RecurringKind.SUBSCRIPTION, kindOf("ZORBLAX 998877", "subscriptions.streaming"));
        assertEquals(RecurringKind.SUBSCRIPTION, kindOf("ZORBLAX 998877", "entertainment.gaming"));
        assertEquals(RecurringKind.SUBSCRIPTION, kindOf("UPI NETFLIX COM 4455667", null));
    }

    @Test
    void kindIsOtherOtherwise() {
        assertEquals(RecurringKind.OTHER, kindOf("ZORBLAX GYM CLUB", null));
        assertEquals(RecurringKind.OTHER, kindOf("ZORBLAX GYM CLUB", "fitness.gym"));
    }

    @Test
    void merchantKeyDropsReferenceNumbersAndNoise() {
        assertEquals(RecurringDetector.merchantKey("UPI/NETFLIX COM/123456789"), RecurringDetector.merchantKey("NETFLIX COM 998877"));
        assertEquals("Netflix Com", RecurringDetector.displayName("NETFLIX COM"));
    }

    private static RecurringKind kindOf(String description, String category) {
        List<RecurringDetector.Payment> p = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            p.add(pay(TODAY.minusDays(10).minusMonths(i), "1000", description, category));
        }
        String key = RecurringDetector.merchantKey(description);
        return RecurringDetector.detect(p, TODAY, key).orElseThrow().kind();
    }
}
