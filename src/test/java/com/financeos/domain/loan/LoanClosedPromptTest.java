package com.financeos.domain.loan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanClosedPromptTest {

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

    private static InstallmentDto installment(int seq, LocalDate due, String status) {
        return new InstallmentDto(seq, due, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, status, null);
    }

    private static Instant daysAgo(int days) {
        return TODAY.minusDays(days).atTime(12, 0).atZone(IST).toInstant();
    }

    @Test
    void asksWhenTheOldestUnpaidInstallmentIs45DaysOverdue() {
        List<InstallmentDto> schedule = List.of(installment(1, TODAY.minusDays(100), "settled"),
                installment(2, TODAY.minusDays(45), "overdue"), installment(3, TODAY.minusDays(15), "overdue"));

        assertTrue(LoanClosedPrompt.ask(LoanStatus.active, schedule, null, TODAY));
    }

    @Test
    void doesNotAskAt44DaysOverdue() {
        assertFalse(LoanClosedPrompt.ask(LoanStatus.active, List.of(installment(1, TODAY.minusDays(44), "overdue")), null, TODAY));
    }

    @Test
    void usesTheOldestUnpaidNotTheNewestOne() {
        List<InstallmentDto> schedule = List.of(installment(1, TODAY.minusDays(60), "overdue"), installment(2, TODAY.minusDays(2), "upcoming"));

        assertTrue(LoanClosedPrompt.ask(LoanStatus.active, schedule, null, TODAY));
    }

    @Test
    void doesNotAskWhenNothingIsUnpaidOrTheLoanIsNotActive() {
        assertFalse(LoanClosedPrompt.ask(LoanStatus.active, List.of(installment(1, TODAY.minusDays(90), "settled")), null, TODAY));
        assertFalse(LoanClosedPrompt.ask(LoanStatus.closed, List.of(installment(1, TODAY.minusDays(90), "overdue")), null, TODAY));
        assertFalse(LoanClosedPrompt.ask(LoanStatus.foreclosed, List.of(installment(1, TODAY.minusDays(90), "overdue")), null, TODAY));
        assertFalse(LoanClosedPrompt.ask(LoanStatus.active, List.of(), null, TODAY));
    }

    @Test
    void aDismissalRestsThePromptFor45Days() {
        List<InstallmentDto> schedule = List.of(installment(1, TODAY.minusDays(90), "overdue"));

        assertFalse(LoanClosedPrompt.ask(LoanStatus.active, schedule, daysAgo(0), TODAY));
        assertFalse(LoanClosedPrompt.ask(LoanStatus.active, schedule, daysAgo(44), TODAY));
        assertTrue(LoanClosedPrompt.ask(LoanStatus.active, schedule, daysAgo(45), TODAY));
        assertTrue(LoanClosedPrompt.ask(LoanStatus.active, schedule, daysAgo(80), TODAY));
    }
}
