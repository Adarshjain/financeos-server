package com.financeos.domain.obligations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.statement.StatementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/** askClosed on Upcoming EMI rows: "Is this loan closed?". */
class ObligationsAskClosedTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private final LoanService loanService = mock(LoanService.class);
    private final ObligationsService service = new ObligationsService(loanService, mock(LendingService.class),
            mock(CardBillService.class), mock(AccountRepository.class), mock(StatementRepository.class));
    private final UUID userId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        LoanResponse loan = new LoanResponse(loanId, "Car loan", null, null, null, null, null, null, null, null, null, null, null,
                LoanStatus.active, null, null, null, null, null, null, null, null, null, null, null, null, null, false, null, null);
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of(loan)));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static InstallmentDto inst(int seq, LocalDate due, String status) {
        return new InstallmentDto(seq, due, null, new BigDecimal("9000"), null, null, null, status, null);
    }

    private List<ObligationItemDto> emiRows() {
        return service.upcoming(userId, 3, Set.of("emi")).items();
    }

    @Test
    void everyEmiRowOfALoanWhoseOldestUnpaidInstallmentIs45DaysOverdueAsks() {
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.minusDays(50), "overdue"), inst(2, TODAY.minusDays(20), "overdue")));

        List<ObligationItemDto> rows = emiRows();

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(ObligationItemDto::askClosed));
    }

    @Test
    void aLoanOnlyRecentlyBehindDoesNotAsk() {
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.minusDays(44), "overdue")));

        assertFalse(emiRows().get(0).askClosed());
    }

    @Test
    void aDismissedPromptDoesNotAskWithin45DaysAndAsksAgainAfter() {
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.minusDays(90), "overdue")));
        Instant tenDaysAgo = TODAY.minusDays(10).atTime(12, 0).atZone(IST).toInstant();
        when(loanService.closedPromptDismissals()).thenReturn(Map.of(loanId, tenDaysAgo));
        assertFalse(emiRows().get(0).askClosed());

        Instant fiftyDaysAgo = TODAY.minusDays(50).atTime(12, 0).atZone(IST).toInstant();
        when(loanService.closedPromptDismissals()).thenReturn(Map.of(loanId, fiftyDaysAgo));
        assertTrue(emiRows().get(0).askClosed());
    }

    @Test
    void anUpToDateLoanDoesNotAsk() {
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.minusDays(90), "settled"), inst(2, TODAY.plusDays(2), "upcoming")));

        assertFalse(emiRows().get(0).askClosed());
    }
}
