package com.financeos.domain.categorization;

import com.financeos.api.transaction.dto.UncategorizedSummaryResponse;
import com.financeos.domain.categorization.builtin.BuiltinMatch;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepository.UncategorizedRow;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The analysis memo: the engine's answer for a (text, direction, mcc) is computed once, shared by every call
 * and user (it depends on nothing else), bounded, and safe under concurrent calls.
 */
class UncategorizedServiceMemoTest {

    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final CategoryAssigner assigner = mock(CategoryAssigner.class);
    private final AtomicInteger engineCalls = new AtomicInteger();
    private UncategorizedService service;

    @BeforeEach
    void setUp() {
        when(assigner.analyse(any(), any(), isNull(), any())).thenAnswer(i -> {
            engineCalls.incrementAndGet();
            String text = i.getArgument(0);
            return new BuiltinMatch(null, null, null, null, text == null ? null : text.toLowerCase().trim(), false);
        });
        service = new UncategorizedService(transactions, assigner, mock(CategoryCatalog.class),
                mock(TransactionCategoryWriter.class), mock(CategoryRuleRepository.class),
                mock(CategorizationService.class), mock(UserRepository.class));
    }

    @Test
    void repeatedCallsAndRepeatedLinesAnalyseOncePerDistinctKey() {
        UUID user = UUID.randomUUID();
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(
                row("SWIGGY", TransactionType.DEBIT, null), row("SWIGGY", TransactionType.DEBIT, null),
                row("ZOMATO", TransactionType.DEBIT, null)));

        UncategorizedSummaryResponse first = service.summary(user);
        UncategorizedSummaryResponse second = service.summary(user);

        assertEquals(2, engineCalls.get());
        assertEquals(first, second);
        assertEquals(3, first.count());
        assertEquals(1, first.groupCount());
    }

    @Test
    void directionAndMccArePartOfTheKey() {
        UUID user = UUID.randomUUID();
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(
                row("ACME", TransactionType.DEBIT, null), row("ACME", TransactionType.CREDIT, null),
                row("ACME", TransactionType.DEBIT, "5411")));

        service.summary(user);

        assertEquals(3, engineCalls.get());
    }

    @Test
    void theMemoIsSharedAcrossUsersAndGivesEachTheirOwnGroups() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(transactions.findUncategorizedRows(a)).thenReturn(List.of(
                row("SHARED", TransactionType.DEBIT, null), row("SHARED", TransactionType.DEBIT, null)));
        when(transactions.findUncategorizedRows(b)).thenReturn(List.of(
                row("SHARED", TransactionType.DEBIT, null), row("ONLY B", TransactionType.DEBIT, null)));

        UncategorizedSummaryResponse forA = service.summary(a);
        UncategorizedSummaryResponse forB = service.summary(b);

        assertEquals(2, engineCalls.get(), "SHARED analysed once for both users");
        assertEquals(2, forA.count());
        assertEquals(1, forA.groupCount());
        assertEquals(2, forB.count(), "B's rows are only B's");
        assertEquals(0, forB.groupCount());
    }

    @Test
    void theMemoIsBoundedAndEvictsTheLeastRecentlyUsedKey() {
        UUID user = UUID.randomUUID();
        int n = UncategorizedService.MEMO_MAX + 5;
        List<UncategorizedRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row("line " + i, TransactionType.DEBIT, null));
        }
        when(transactions.findUncategorizedRows(user)).thenReturn(rows);
        service.summary(user);
        assertEquals(n, engineCalls.get());

        // The newest line is remembered, the oldest was evicted.
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(row("line " + (n - 1), TransactionType.DEBIT, null)));
        service.summary(user);
        assertEquals(n, engineCalls.get());
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(row("line 0", TransactionType.DEBIT, null)));
        service.summary(user);
        assertEquals(n + 1, engineCalls.get());
    }

    @Test
    void concurrentCallsAreSafeAndAgree() throws Exception {
        UUID user = UUID.randomUUID();
        List<UncategorizedRow> rows = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            rows.add(row("payee " + (i % 100), TransactionType.DEBIT, null));
        }
        when(transactions.findUncategorizedRows(user)).thenReturn(rows);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<UncategorizedSummaryResponse>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit(() -> service.summary(user)));
        }
        for (Future<UncategorizedSummaryResponse> f : results) {
            UncategorizedSummaryResponse r = f.get(30, TimeUnit.SECONDS);
            assertEquals(400, r.count());
            assertEquals(100, r.groupCount());
        }
        pool.shutdownNow();
    }

    private record Row(UUID id, String text, TransactionType type, String mcc) implements UncategorizedRow {
        public UUID getId() { return id; }
        public LocalDate getDate() { return LocalDate.of(2026, 10, 1); }
        public String getSourcedDescription() { return text; }
        public String getDescription() { return null; }
        public TransactionType getType() { return type; }
        public BigDecimal getAmount() { return BigDecimal.TEN; }
        public String getMcc() { return mcc; }
    }

    private static UncategorizedRow row(String text, TransactionType type, String mcc) {
        return new Row(UUID.randomUUID(), text, type, mcc);
    }
}
