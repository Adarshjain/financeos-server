package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.transaction.dto.TransactionSuggestion;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class TransactionSuggestionServiceTest {

    private final AutoLinkStore store = mock(AutoLinkStore.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionSuggestionService service = new TransactionSuggestionService(store, transactions, jdbc);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Transaction tx(String accountName, AccountType accountType, TransactionType type, String amount,
                                  LocalDate date, String description) {
        Account account = new Account(accountName, accountType);
        account.setId(UUID.randomUUID());
        Transaction t = new Transaction(account, date, new BigDecimal(amount), description, TransactionSource.manual, type, false, false);
        t.setId(UUID.randomUUID());
        return t;
    }

    private AutoLinkStore.SuggestionRow row(SuggestionKind kind, Transaction subject, UUID... candidates) {
        return new AutoLinkStore.SuggestionRow(UUID.randomUUID(), kind, subject.getId(), List.of(candidates));
    }

    private void given(List<AutoLinkStore.SuggestionRow> rows, Transaction... txns) {
        when(store.listSuggestionsFor(eq(userId), any())).thenReturn(rows);
        Set<UUID> live = new java.util.HashSet<>();
        for (Transaction t : txns) {
            live.add(t.getId());
        }
        when(store.liveTransactionIds(eq(userId), any())).thenReturn(live);
        when(transactions.findAllByIdInAndUserId(any(), eq(userId))).thenReturn(List.of(txns));
    }

    /** Answers the account last4 query with one row per account id, and the lendings query with the given row. */
    private void jdbcAnswers(Map<UUID, String> last4, UUID lendingId, LocalDate lendingDate, String person) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(1);
            if (sql.contains("account_bank_details")) {
                for (Map.Entry<UUID, String> e : last4.entrySet()) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(e.getKey().toString());
                    when(rs.getString(2)).thenReturn(e.getValue());
                    handler.processRow(rs);
                }
            } else if (sql.contains("lendings") && lendingId != null) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(lendingId.toString());
                when(rs.getDate(2)).thenReturn(java.sql.Date.valueOf(lendingDate));
                when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("8000"));
                when(rs.getString(4)).thenReturn("Trip share");
                when(rs.getString(5)).thenReturn(person);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void transferShowsOnBothSidesWithDirectionalLabels() {
        Transaction debit = tx("ICICI Current", AccountType.bank_account, TransactionType.DEBIT, "5000", LocalDate.of(2026, 11, 1), "NEFT out");
        Transaction credit = tx("HDFC Savings", AccountType.bank_account, TransactionType.CREDIT, "5000", LocalDate.of(2026, 11, 2), "NEFT in");
        AutoLinkStore.SuggestionRow r = row(SuggestionKind.TRANSFER, debit, credit.getId());
        given(List.of(r), debit, credit);
        jdbcAnswers(Map.of(credit.getAccount().getId(), "1234", debit.getAccount().getId(), "9876"), null, null, null);

        Map<UUID, TransactionSuggestion> out = service.suggestionsFor(List.of(debit.getId(), credit.getId()));

        assertThat(out.get(debit.getId()).label()).isEqualTo("Looks like a transfer to HDFC Savings ••1234");
        assertThat(out.get(debit.getId()).kind()).isEqualTo(SuggestionKind.TRANSFER);
        assertThat(out.get(debit.getId()).id()).isEqualTo(r.id());
        assertThat(out.get(debit.getId()).candidates()).singleElement().satisfies(c -> {
            assertThat(c.transactionId()).isEqualTo(credit.getId());
            assertThat(c.lendingId()).isNull();
            assertThat(c.accountName()).isEqualTo("HDFC Savings");
            assertThat(c.amount()).isEqualByComparingTo("5000");
        });
        assertThat(out.get(credit.getId()).label()).isEqualTo("Looks like a transfer from ICICI Current ••9876");
        assertThat(out.get(credit.getId()).id()).isEqualTo(r.id());
        assertThat(out.get(credit.getId()).candidates()).singleElement().satisfies(c -> {
            assertThat(c.transactionId()).isEqualTo(debit.getId());
            assertThat(c.amount()).isEqualByComparingTo("-5000");
        });
    }

    @Test
    void cardPaymentLabelsReadFromEachSide() {
        Transaction debit = tx("HDFC Savings", AccountType.bank_account, TransactionType.DEBIT, "12000", LocalDate.of(2026, 11, 1), "CC bill");
        Transaction credit = tx("HDFC Regalia", AccountType.credit_card, TransactionType.CREDIT, "12000", LocalDate.of(2026, 11, 1), "Payment");
        given(List.of(row(SuggestionKind.CC_PAYMENT, debit, credit.getId())), debit, credit);
        jdbcAnswers(Map.of(debit.getAccount().getId(), "1234"), null, null, null);

        Map<UUID, TransactionSuggestion> out = service.suggestionsFor(List.of(debit.getId(), credit.getId()));

        assertThat(out.get(debit.getId()).label()).isEqualTo("Looks like your HDFC Regalia bill");
        assertThat(out.get(credit.getId()).label()).isEqualTo("Looks like a payment from HDFC Savings ••1234");
    }

    @Test
    void refundShowsOnTheCreditOnlyWithTheDebitItReverses() {
        Transaction credit = tx("HDFC Regalia", AccountType.credit_card, TransactionType.CREDIT, "450", LocalDate.of(2026, 11, 5), "SWIGGY REFUND");
        Transaction debit = tx("HDFC Regalia", AccountType.credit_card, TransactionType.DEBIT, "450", LocalDate.of(2026, 11, 2), "Swiggy");
        given(List.of(row(SuggestionKind.REFUND, credit, debit.getId())), credit, debit);
        jdbcAnswers(Map.of(), null, null, null);

        Map<UUID, TransactionSuggestion> out = service.suggestionsFor(List.of(credit.getId(), debit.getId()));

        assertThat(out.get(credit.getId()).label()).isEqualTo("Looks like a refund of Swiggy ₹450 · 2 Nov");
        assertThat(out).doesNotContainKey(debit.getId());
    }

    @Test
    void lendingSuggestionNamesThePersonAndOffersTheLendingEntry() {
        Transaction credit = tx("HDFC Savings", AccountType.bank_account, TransactionType.CREDIT, "5000", LocalDate.of(2026, 11, 5), "UPI RAHUL");
        UUID lendingId = UUID.randomUUID();
        given(List.of(row(SuggestionKind.LENDING, credit, lendingId)), credit);
        jdbcAnswers(Map.of(), lendingId, LocalDate.of(2026, 10, 1), "Rahul");

        Map<UUID, TransactionSuggestion> out = service.suggestionsFor(List.of(credit.getId()));

        TransactionSuggestion s = out.get(credit.getId());
        assertThat(s.label()).isEqualTo("Looks like Rahul paying back ₹5,000");
        assertThat(s.kind()).isEqualTo(SuggestionKind.LENDING);
        assertThat(s.candidates()).singleElement().satisfies(c -> {
            assertThat(c.lendingId()).isEqualTo(lendingId);
            assertThat(c.transactionId()).isNull();
            assertThat(c.amount()).isEqualByComparingTo("8000");
        });
    }

    @Test
    void aPageIsOneSuggestionQueryAndOneTransactionLoadNotOnePerRow() {
        Transaction d1 = tx("A", AccountType.bank_account, TransactionType.DEBIT, "1", LocalDate.of(2026, 11, 1), "x");
        Transaction c1 = tx("B", AccountType.bank_account, TransactionType.CREDIT, "1", LocalDate.of(2026, 11, 1), "x");
        Transaction d2 = tx("A", AccountType.bank_account, TransactionType.DEBIT, "2", LocalDate.of(2026, 11, 1), "x");
        Transaction c2 = tx("B", AccountType.bank_account, TransactionType.CREDIT, "2", LocalDate.of(2026, 11, 1), "x");
        given(List.of(row(SuggestionKind.TRANSFER, d1, c1.getId()), row(SuggestionKind.TRANSFER, d2, c2.getId())), d1, c1, d2, c2);
        jdbcAnswers(Map.of(), null, null, null);

        Map<UUID, TransactionSuggestion> out = service.suggestionsFor(List.of(d1.getId(), c1.getId(), d2.getId(), c2.getId()));

        assertThat(out).hasSize(4);
        verify(store, times(1)).listSuggestionsFor(eq(userId), any());
        verify(store, times(1)).liveTransactionIds(eq(userId), any());
        verify(transactions, times(1)).findAllByIdInAndUserId(any(), eq(userId));
        verify(jdbc, times(1)).query(contains("account_bank_details"), any(RowCallbackHandler.class), any(Object[].class));
    }

    private static String contains(String part) {
        return org.mockito.ArgumentMatchers.contains(part);
    }

    @Test
    void aSuggestionWhoseTransactionsAreNoLongerFreeIsNotShown() {
        Transaction debit = tx("A", AccountType.bank_account, TransactionType.DEBIT, "1", LocalDate.of(2026, 11, 1), "x");
        Transaction credit = tx("B", AccountType.bank_account, TransactionType.CREDIT, "1", LocalDate.of(2026, 11, 1), "x");
        when(store.listSuggestionsFor(eq(userId), any())).thenReturn(List.of(row(SuggestionKind.TRANSFER, debit, credit.getId())));
        when(store.liveTransactionIds(eq(userId), any())).thenReturn(Set.of(debit.getId())); // credit got linked elsewhere
        when(transactions.findAllByIdInAndUserId(any(), eq(userId))).thenReturn(List.of(debit));
        jdbcAnswers(Map.of(), null, null, null);

        assertThat(service.suggestionsFor(List.of(debit.getId(), credit.getId()))).isEmpty();
    }

    @Test
    void everythingIsReadForTheCurrentUserOnlyAndNobodyGetsNothing() {
        UUID other = UUID.randomUUID();
        when(store.listSuggestionsFor(eq(userId), any())).thenReturn(List.of());

        assertThat(service.suggestionsFor(List.of(other))).isEmpty();
        verify(store).listSuggestionsFor(eq(userId), any());
        verify(store, never()).listSuggestionsFor(eq(other), any());

        UserContext.clear();
        assertThat(service.suggestionsFor(List.of(other))).isEmpty();
        verify(store, times(1)).listSuggestionsFor(any(), any());
    }

    @Test
    void anEmptyPageDoesNotQuery() {
        assertThat(service.suggestionsFor(List.of())).isEmpty();
        verify(store, never()).listSuggestionsFor(any(), any());
    }
}
