package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementExpectedInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final StatementExpectedInboxCollector collector = new StatementExpectedInboxCollector(accountRepository, statementRepository);
    private final UUID userId = UUID.randomUUID();
    private Account card;

    @BeforeEach
    void setUp() {
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        Cardholder holder = new Cardholder();
        holder.setId(UUID.randomUUID());
        holder.setAccount(card);
        holder.setRole(CardholderRole.PRIMARY);
        Card plastic = new Card();
        plastic.setId(UUID.randomUUID());
        plastic.setAccount(card);
        plastic.setCardholder(holder);
        plastic.setLast4("1234");
        holder.getCards().add(plastic);
        card.getCardholders().add(holder);
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
    }

    /** Monthly statements closing on the 15th, the last one ending on {@code lastEnd}. */
    private void statementsUntil(LocalDate lastEnd) {
        List<Statement> list = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            Statement s = new Statement();
            s.setId(UUID.randomUUID());
            LocalDate end = lastEnd.minusMonths(i);
            s.setPeriodEnd(end);
            s.setPeriodStart(end.minusMonths(1).plusDays(1));
            s.setStatementType("credit_card");
            list.add(s);
        }
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(list);
    }

    @Test
    void aCardPastTheGracePeriodGetsAMissingStatementRowKeyedByTheOverduePeriodEnd() {
        // Last statement 15 Jul: the 15 Aug and 15 Sep closes are both past grace; the latest one keys the row.
        statementsUntil(LocalDate.of(2026, 7, 15));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        String href = "/transactions/import?account=" + card.getId();
        assertEquals("statement-expected:" + card.getId() + ":2026-09-15", row.key());
        assertEquals("statement_expected", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("HDFC Regalia ••1234 statement missing", row.title());
        assertEquals("Expected by 20 Sep · period ended 15 Sep", row.subtitle());
        assertEquals(href, row.href());
        assertEquals(null, row.amount());
        assertEquals(LocalDate.of(2026, 9, 15), row.date());
        assertEquals(List.of(InboxActionResponse.navigate("upload", "Upload", href), InboxRows.snooze()), row.actions());
        assertEquals(InboxRefsResponse.ofAccount(card.getId()), row.refs());
    }

    @Test
    void aCardStillInsideTheGracePeriodOrWithoutStatementsHasNoRow() {
        statementsUntil(LocalDate.of(2026, 9, 15)); // next close 15 Oct, grace through 20 Oct
        assertEquals(List.of(), collector.collect(userId, TODAY));

        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of());
        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void closedCardsAreSkippedWithoutLookingAtStatements() {
        card.setClosedOn(TODAY);
        statementsUntil(LocalDate.of(2026, 7, 15));

        assertEquals(List.of(), collector.collect(userId, TODAY));
        verify(statementRepository, never()).findQualifyingCreditCardStatements(any());
    }

    @Test
    void aCardClosingLaterIsStillOpenToday() {
        card.setClosedOn(TODAY.plusDays(1));
        statementsUntil(LocalDate.of(2026, 7, 15));

        assertEquals(1, collector.collect(userId, TODAY).size());
    }
}
