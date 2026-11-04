package com.financeos.domain.obligations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/** The Upcoming "statement expected" row advances past cycles marked "No statement this period". */
class ObligationsSkippedPeriodTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private StatementSkippedPeriodRepository skipRepository;
    private ObligationsService service;
    private final UUID userId = UUID.randomUUID();
    private Account card;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        LoanService loanService = mock(LoanService.class);
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        skipRepository = mock(StatementSkippedPeriodRepository.class);
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        service = new ObligationsService(loanService, mock(LendingService.class), mock(CardBillService.class),
                accountRepository, statementRepository);
        service.setSkippedRepository(skipRepository);
        card = new Account("Axis Ace", AccountType.credit_card);
        card.setId(UUID.randomUUID());
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        Statement s = new Statement();
        s.setPeriodStart(LocalDate.of(2026, 8, 16));
        s.setPeriodEnd(LocalDate.of(2026, 9, 15));
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of(s));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void skips(LocalDate... ends) {
        List<StatementSkippedPeriodRepository.SkippedEnd> rows = new java.util.ArrayList<>();
        for (LocalDate end : ends) {
            StatementSkippedPeriodRepository.SkippedEnd row = mock(StatementSkippedPeriodRepository.SkippedEnd.class);
            when(row.getAccountId()).thenReturn(card.getId());
            when(row.getPeriodEnd()).thenReturn(end);
            rows.add(row);
        }
        when(skipRepository.findEndsByAccountIds(any())).thenReturn(rows);
    }

    private List<ObligationItemDto> items() {
        return service.upcoming(userId, 3, Set.of("statement_expected")).items();
    }

    @Test
    void withoutASkipTheNextCycleEndIsExpected() {
        assertEquals(LocalDate.of(2026, 10, 15), items().get(0).date());
    }

    @Test
    void aSkippedCycleMovesTheExpectedDateToTheNextCycle() {
        skips(LocalDate.of(2026, 10, 15));

        ObligationItemDto i = items().get(0);

        assertEquals(LocalDate.of(2026, 11, 15), i.date());
        assertEquals(card.getId(), i.accountId());
    }

    @Test
    void aSkipBeforeTheLatestStatementChangesNothing() {
        skips(LocalDate.of(2026, 8, 15));

        assertEquals(LocalDate.of(2026, 10, 15), items().get(0).date());
    }

    @Test
    void aSkipDoesNotCreateARowForACardWithoutStatements() {
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());
        skips(LocalDate.of(2026, 10, 15));

        assertTrue(items().isEmpty());
    }
}
