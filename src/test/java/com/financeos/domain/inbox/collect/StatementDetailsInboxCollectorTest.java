package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.inbox.InboxItemState;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatementDetailsInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final StatementDetailsInboxCollector collector = new StatementDetailsInboxCollector(statementRepository);
    private final UUID userId = UUID.randomUUID();

    private Account card(String name, String last4) {
        Account card = new Account();
        card.setId(UUID.randomUUID());
        card.setName(name);
        card.setType(AccountType.credit_card);
        Cardholder holder = new Cardholder();
        holder.setId(UUID.randomUUID());
        holder.setAccount(card);
        holder.setRole(CardholderRole.PRIMARY);
        Card plastic = new Card();
        plastic.setId(UUID.randomUUID());
        plastic.setAccount(card);
        plastic.setCardholder(holder);
        plastic.setLast4(last4);
        holder.getCards().add(plastic);
        card.getCardholders().add(holder);
        return card;
    }

    private Statement statement(Account card, LocalDate end, LocalDate due, BigDecimal total, BigDecimal min,
                                StatementVerdict verdict) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setAccount(card);
        s.setStatementType("credit_card");
        s.setVerdict(verdict);
        s.setPeriodEnd(end);
        s.setPeriodStart(end == null ? null : end.minusDays(29));
        s.setCreatedAt(Instant.now());
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setPaymentDueDate(due);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(min);
        s.setCreditCardDetails(d);
        return s;
    }

    private Statement gap(Account card, LocalDate end) {
        return statement(card, end, null, new BigDecimal("100"), new BigDecimal("5"), StatementVerdict.AUTO_INGEST);
    }

    private void repoReturns(Statement... statements) {
        when(statementRepository.findLiveCreditCardStatementsByUser(userId)).thenReturn(new ArrayList<>(List.of(statements)));
    }

    @Test
    void oneRowPerCardWithTheCountAndTheNewestStatementInTheLink() {
        Account a = card("HDFC Regalia", "1234");
        Account b = card("ICICI Amazon", "9876");
        Statement newest = gap(a, LocalDate.of(2026, 9, 30));
        // The repository hands them newest first.
        repoReturns(newest, gap(a, LocalDate.of(2026, 8, 31)), gap(a, LocalDate.of(2026, 7, 31)),
                gap(b, LocalDate.of(2026, 6, 30)));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(2, rows.size());
        InboxItemResponse rowA = rows.stream().filter(r -> r.key().endsWith(a.getId().toString())).findFirst().orElseThrow();
        assertEquals("statement-details-missing:" + a.getId(), rowA.key());
        assertEquals(InboxKinds.STATEMENT_DETAILS_MISSING, rowA.kind());
        assertEquals("warning", rowA.severity());
        assertEquals("Statement details missing", rowA.title());
        assertEquals("HDFC Regalia ••1234 · 3 statements", rowA.subtitle());
        assertEquals("/ds/accounts/" + a.getId() + "/statements?statement=" + newest.getId(), rowA.href());
        assertEquals(newest.getId(), rowA.refs().statementId());
        assertEquals(List.of("open", "snooze"), rowA.actions().stream().map(InboxActionResponse::type).toList());
    }

    @Test
    void aSingleStatementNamesTheMonthAndTheMissingFields() {
        Account a = card("HDFC Regalia", "1234");
        repoReturns(statement(a, LocalDate.of(2026, 9, 30), null, new BigDecimal("100"), null, StatementVerdict.NEEDS_REVIEW));

        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        assertEquals("HDFC Regalia ••1234 · Sep 2026 statement · due date, minimum due", row.subtitle());
    }

    @Test
    void aStatementWithoutAPeriodFallsBackToItsCreatedMonth() {
        AppTime.useClock(java.time.Clock.fixed(Instant.parse("2026-10-20T04:30:00Z"), java.time.ZoneId.of("Asia/Kolkata")));
        try {
            Account a = card("HDFC Regalia", "1234");
            Statement s = statement(a, null, LocalDate.of(2026, 10, 5), BigDecimal.TEN, BigDecimal.ONE, null);
            s.setCreatedAt(Instant.parse("2026-10-02T04:00:00Z"));
            repoReturns(s);

            assertEquals("HDFC Regalia ••1234 · Oct 2026 statement · period end",
                    collector.collect(userId, TODAY).get(0).subtitle());
        } finally {
            AppTime.reset();
        }
    }

    @Test
    void completeAndRejectedStatementsAndClosedCardsAddNothing() {
        Account a = card("HDFC Regalia", "1234");
        Account closed = card("Old card", "0001");
        closed.setClosedOn(LocalDate.of(2026, 1, 1));
        repoReturns(
                statement(a, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 18), BigDecimal.TEN, BigDecimal.ONE,
                        StatementVerdict.AUTO_INGEST),
                statement(a, LocalDate.of(2026, 8, 31), null, null, null, StatementVerdict.REJECTED),
                gap(closed, LocalDate.of(2025, 12, 31)));

        assertTrue(collector.collect(userId, TODAY).isEmpty());
    }

    @Test
    void thePlainRowIsSnoozeableButNotDismissable() {
        Account a = card("HDFC Regalia", "1234");
        repoReturns(gap(a, LocalDate.of(2026, 9, 30)));
        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        assertTrue(row.actions().stream().noneMatch(x -> "dismiss".equals(x.type())));
        InboxItemState state = new InboxItemState(userId, row.key());
        state.setSnoozedUntil(TODAY.plusDays(3));
        assertTrue(state.hides(TODAY), "a snoozed row is hidden until its date");
        assertTrue(!state.hides(TODAY.plusDays(3)));
    }
}
