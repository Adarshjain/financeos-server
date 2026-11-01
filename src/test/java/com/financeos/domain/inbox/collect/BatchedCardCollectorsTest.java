package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The statement-expected collector reads every card with IN queries; with several cards the rows
 * must equal what running the collector one card at a time (the old per-card reads) produces.
 */
class BatchedCardCollectorsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private final UUID userId = UUID.randomUUID();

    private Account card(String name, LocalDate closedOn) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setType(AccountType.credit_card);
        a.setClosedOn(closedOn);
        return a;
    }

    private AccountRepository accounts(Account... cards) {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(cards));
        return repo;
    }

    private static <T> List<T> forAccounts(List<T> all, Collection<UUID> ids, java.util.function.Function<T, UUID> accountOf) {
        return all.stream().filter(x -> ids.contains(accountOf.apply(x))).toList();
    }

    // ---------------------------------------------------------------- statements expected

    private Statement statement(Account c, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setAccount(c);
        s.setPeriodEnd(end);
        s.setPeriodStart(end.minusMonths(1).plusDays(1));
        s.setStatementType("credit_card");
        return s;
    }

    private StatementExpectedInboxCollector expected(AccountRepository accounts, StatementRepository sr, List<Statement> all) {
        when(sr.findQualifyingCreditCardStatementsIn(any())).thenAnswer(inv -> forAccounts(all, inv.getArgument(0), s -> s.getAccount().getId()));
        return new StatementExpectedInboxCollector(accounts, sr);
    }

    @Test
    void missingStatementRowsForSeveralCardsEqualTheOneCardAtATimeRowsAndCostOneQuery() {
        Account lapsed = card("Lapsed", null);
        Account current = card("Current", null);
        Account never = card("Never", null);
        Account closed = card("Closed", LocalDate.of(2026, 1, 1));
        List<Statement> all = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            all.add(statement(lapsed, LocalDate.of(2026, 7, 15).minusMonths(i)));
            all.add(statement(current, LocalDate.of(2026, 9, 15).minusMonths(i)));
            all.add(statement(closed, LocalDate.of(2026, 7, 15).minusMonths(i)));
        }
        StatementRepository sr = mock(StatementRepository.class);

        List<InboxItemResponse> batched = expected(accounts(lapsed, current, never, closed), sr, all).collect(userId, TODAY);

        List<InboxItemResponse> oneByOne = new ArrayList<>();
        for (Account c : List.of(lapsed, current, never, closed)) {
            oneByOne.addAll(expected(accounts(c), mock(StatementRepository.class), all).collect(userId, TODAY));
        }
        assertEquals(oneByOne, batched);
        assertFalse(batched.isEmpty());
        verify(sr, times(1)).findQualifyingCreditCardStatementsIn(any());
    }
}
