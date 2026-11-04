package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionPayloadResponse;
import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The "statement missing" Inbox row offers "No statement this period" and disappears once that cycle is skipped. */
class StatementExpectedSkipActionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final StatementSkippedPeriodRepository skipRepository = mock(StatementSkippedPeriodRepository.class);
    private final StatementExpectedInboxCollector collector = new StatementExpectedInboxCollector(accountRepository, statementRepository);
    private final UUID userId = UUID.randomUUID();
    private Account card;

    @BeforeEach
    void setUp() {
        collector.setSkippedRepository(skipRepository);
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        List<Statement> list = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            Statement s = new Statement();
            s.setId(UUID.randomUUID());
            s.setAccount(card);
            LocalDate end = LocalDate.of(2026, 7, 15).minusMonths(i);
            s.setPeriodEnd(end);
            s.setPeriodStart(end.minusMonths(1).plusDays(1));
            s.setStatementType("credit_card");
            list.add(s);
        }
        when(statementRepository.findQualifyingCreditCardStatementsIn(List.of(card.getId()))).thenReturn(list);
    }

    private void skips(LocalDate... ends) {
        List<StatementSkippedPeriodRepository.SkippedEnd> rows = new ArrayList<>();
        for (LocalDate end : ends) {
            StatementSkippedPeriodRepository.SkippedEnd row = mock(StatementSkippedPeriodRepository.SkippedEnd.class);
            when(row.getAccountId()).thenReturn(card.getId());
            when(row.getPeriodEnd()).thenReturn(end);
            rows.add(row);
        }
        when(skipRepository.findEndsByAccountIds(any())).thenReturn(rows);
    }

    @Test
    void theRowCarriesANoStatementActionForTheOverdueCycle() {
        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        InboxActionResponse action = row.actions().get(2);
        assertEquals("no_statement", action.type());
        assertEquals("No statement this period", action.label());
        assertEquals(card.getId(), action.payload().accountId());
        assertEquals(LocalDate.of(2026, 9, 15), action.payload().date());
    }

    @Test
    void skippingTheOverdueCycleRemovesTheRow() {
        skips(LocalDate.of(2026, 9, 15));

        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void skippingAnEarlierCycleKeepsTheLaterOverdueRow() {
        skips(LocalDate.of(2026, 8, 15));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        assertEquals(LocalDate.of(2026, 9, 15), rows.get(0).date());
    }
}
