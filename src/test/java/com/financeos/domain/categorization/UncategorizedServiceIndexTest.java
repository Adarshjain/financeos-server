package com.financeos.domain.categorization;

import com.financeos.domain.categorization.builtin.BuiltinMatch;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepository.UncategorizedFingerprint;
import com.financeos.domain.transaction.TransactionRepository.UncategorizedRow;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import com.financeos.api.transaction.dto.UncategorizedSummaryResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The per-user group index is reused while the freshness probe is unchanged and rebuilt when it moves. */
class UncategorizedServiceIndexTest {

    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final CategoryAssigner assigner = mock(CategoryAssigner.class);
    private UncategorizedService service;

    @BeforeEach
    void setUp() {
        when(assigner.analyse(any(), any(), isNull(), any())).thenAnswer(i -> {
            String text = i.getArgument(0);
            return new BuiltinMatch(null, null, null, null, text.toLowerCase().trim(), false);
        });
        service = new UncategorizedService(transactions, assigner, mock(CategoryCatalog.class),
                mock(TransactionCategoryWriter.class), mock(CategoryRuleRepository.class),
                mock(CategorizationService.class), mock(UserRepository.class));
    }

    private static UncategorizedFingerprint fp(long count, long updatedMillis) {
        return new UncategorizedFingerprint() {
            public long getCount() { return count; }
            public Instant getMaxUpdatedAt() { return Instant.ofEpochMilli(updatedMillis); }
        };
    }

    private static UncategorizedRow row(String text) {
        UUID id = UUID.randomUUID();
        return new UncategorizedRow() {
            public UUID getId() { return id; }
            public LocalDate getDate() { return LocalDate.of(2026, 10, 1); }
            public String getSourcedDescription() { return text; }
            public String getDescription() { return null; }
            public TransactionType getType() { return TransactionType.DEBIT; }
            public BigDecimal getAmount() { return BigDecimal.TEN; }
            public String getMcc() { return null; }
        };
    }

    @Test
    void summaryPageAndGroupOpenShareOneBuildWhileTheProbeIsUnchanged() {
        UUID user = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(3, 1000));
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(row("A"), row("A"), row("B")));

        service.summary(user);
        var page = service.groupPage(user, PageRequest.of(0, 10));
        service.groupPage(user, "a", PageRequest.of(0, 10));
        service.groupTransactions(user, page.getContent().get(0).groupKey(), PageRequest.of(0, 10));

        verify(transactions, times(1)).findUncategorizedRows(user);
        verify(transactions, times(4)).uncategorizedFingerprint(user);
    }

    @Test
    void aChangedCountOrNewestUpdateRebuilds() {
        UUID user = UUID.randomUUID();
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(row("A"), row("A")));
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(2, 1000));
        service.summary(user);

        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(2, 2000)); // an edit
        service.summary(user);
        verify(transactions, times(2)).findUncategorizedRows(user);

        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(1, 2000)); // a row left
        service.summary(user);
        verify(transactions, times(3)).findUncategorizedRows(user);
    }

    @Test
    void indexesAreKeptPerUser() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(a)).thenReturn(fp(2, 1000));
        when(transactions.uncategorizedFingerprint(b)).thenReturn(fp(2, 1000));
        when(transactions.findUncategorizedRows(a)).thenReturn(List.of(row("MINE"), row("MINE")));
        when(transactions.findUncategorizedRows(b)).thenReturn(List.of(row("THEIRS"), row("THEIRS")));

        assertEquals("Mine", service.groupPage(a, PageRequest.of(0, 10)).getContent().get(0).label());
        assertEquals("Theirs", service.groupPage(b, PageRequest.of(0, 10)).getContent().get(0).label());
        assertEquals("Mine", service.groupPage(a, PageRequest.of(0, 10)).getContent().get(0).label());
        verify(transactions, times(1)).findUncategorizedRows(a);
    }

    @Test
    void theCacheIsBoundedByUsers() {
        int n = UncategorizedService.INDEX_MAX_USERS + 3;
        UUID first = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(any())).thenReturn(fp(1, 1000));
        when(transactions.findUncategorizedRows(any())).thenReturn(List.of(row("A")));
        service.summary(first);
        for (int i = 0; i < n; i++) {
            service.summary(UUID.randomUUID());
        }
        service.summary(first);
        verify(transactions, times(2)).findUncategorizedRows(first);
    }

    @Test
    void searchMatchesTheLabelAnywhereIgnoringCase() {
        UUID user = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(3, 1000));
        when(transactions.findUncategorizedRows(user)).thenReturn(List.of(row("SWIGGY"), row("ZOMATO"), row("Big Swig")));

        assertEquals(2, service.groupPage(user, "SWIG", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, service.groupPage(user, "mato", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(3, service.groupPage(user, "  ", PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void prewarmBuildsInTheBackgroundWithoutBlockingAndLaterCallsAreWarm() throws Exception {
        UUID user = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(2, 1000));
        when(transactions.findUncategorizedRows(user)).thenAnswer(i -> {
            Thread.sleep(600);
            return List.of(row("A"), row("A"));
        });

        long t0 = System.nanoTime();
        service.prewarm(user);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 300, "prewarm must not wait for the build, took " + ms);

        verify(transactions, timeout(5000).times(1)).findUncategorizedRows(user);
        long deadline = System.currentTimeMillis() + 5000;
        UncategorizedSummaryResponse s = service.summary(user); // joins the running build or hits the cache
        assertEquals(2, s.count());
        verify(transactions, times(1)).findUncategorizedRows(user);
        assertTrue(System.currentTimeMillis() < deadline);
    }

    @Test
    void concurrentCallsAndPrewarmBuildOnce() throws Exception {
        UUID user = UUID.randomUUID();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(2, 1000));
        when(transactions.findUncategorizedRows(user)).thenAnswer(i -> {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return List.of(row("A"), row("A"));
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<UncategorizedSummaryResponse>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(pool.submit(() -> service.summary(user)));
        }
        service.prewarm(user);
        Thread.sleep(300);
        release.countDown();
        for (var f : results) {
            assertEquals(2, f.get(10, java.util.concurrent.TimeUnit.SECONDS).count());
        }
        pool.shutdownNow();
        verify(transactions, times(1)).findUncategorizedRows(user);
    }

    @Test
    void aFailedPrewarmIsSwallowedAndTheNextCallStillWorks() {
        UUID user = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(1, 1000));
        when(transactions.findUncategorizedRows(user)).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(row("A")));

        service.prewarm(user);
        verify(transactions, timeout(5000).times(1)).findUncategorizedRows(user);

        assertEquals(1, service.summary(user).count());
    }

    @Test
    void aCallJoiningASlowBuildGivesUpAfterTheTimeoutAndBuildsItself() throws Exception {
        UUID user = UUID.randomUUID();
        service.joinTimeoutMillis = 150;
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(1, 1000));
        when(transactions.findUncategorizedRows(user)).thenAnswer(i -> {
            if (reads.incrementAndGet() == 1) {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS); // the stuck first build
            }
            return List.of(row("A"));
        });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var first = pool.submit(() -> service.summary(user));
        verify(transactions, timeout(5000).times(1)).findUncategorizedRows(user);

        long t0 = System.nanoTime();
        assertEquals(1, service.summary(user).count());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 5000, "did not wait for the stuck build, took " + ms);
        verify(transactions, times(2)).findUncategorizedRows(user);
        release.countDown();
        first.get(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    @Test
    void anOlderBuildNeverOverwritesANewerIndex() throws Exception {
        UUID user = UUID.randomUUID();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(1, 1000));
        when(transactions.findUncategorizedRows(user)).thenAnswer(i -> {
            if (reads.incrementAndGet() == 1) {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS); // old build, started first, finishes last
                return List.of(row("OLD"));
            }
            return List.of(row("NEW"), row("NEW"));
        });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var old = pool.submit(() -> service.summary(user));
        verify(transactions, timeout(5000).times(1)).findUncategorizedRows(user);

        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(2, 2000)); // the set changed meanwhile
        assertEquals(2, service.summary(user).count());
        release.countDown();
        old.get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(2, service.summary(user).count(), "still the newer index");
        verify(transactions, times(2)).findUncategorizedRows(user);
        pool.shutdownNow();
    }

    @Test
    void theCacheIsBoundedByTotalRowIdsEvictingTheLeastRecentlyUsed() {
        service.maxCachedRows = 5;
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID u : List.of(a, b)) {
            when(transactions.uncategorizedFingerprint(u)).thenReturn(fp(3, 1000));
            when(transactions.findUncategorizedRows(u)).thenReturn(List.of(row("A"), row("A"), row("A")));
        }
        service.summary(a);
        service.summary(b); // 3 + 3 > 5: a is dropped
        assertEquals(1, service.cachedUsers());
        service.summary(b);
        verify(transactions, times(1)).findUncategorizedRows(b);
        service.summary(a);
        verify(transactions, times(2)).findUncategorizedRows(a);
    }

    @Test
    void expiredEntriesAreDroppedWhenAnotherIndexIsStored() throws Exception {
        service.indexTtlMillis = 50;
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID u : List.of(a, b)) {
            when(transactions.uncategorizedFingerprint(u)).thenReturn(fp(1, 1000));
            when(transactions.findUncategorizedRows(u)).thenReturn(List.of(row("A")));
        }
        service.summary(a);
        assertEquals(1, service.cachedUsers());
        Thread.sleep(120);

        service.summary(b);

        assertEquals(1, service.cachedUsers(), "a expired and was removed, not left until it is read again");
    }

    @Test
    void aUserAboveTheBudgetIsServedUncachedWithoutEvictingOthers() {
        service.maxCachedRows = 5;
        UUID small = UUID.randomUUID();
        UUID big = UUID.randomUUID();
        when(transactions.uncategorizedFingerprint(small)).thenReturn(fp(2, 1000));
        when(transactions.findUncategorizedRows(small)).thenReturn(List.of(row("A"), row("A")));
        when(transactions.uncategorizedFingerprint(big)).thenReturn(fp(6, 1000));
        when(transactions.findUncategorizedRows(big)).thenReturn(
                List.of(row("B"), row("B"), row("B"), row("B"), row("B"), row("B")));

        service.summary(small);
        assertEquals(6, service.summary(big).count());
        assertEquals(1, service.cachedUsers(), "big was not cached and small was not evicted");
        service.summary(small);
        verify(transactions, times(1)).findUncategorizedRows(small);
        service.summary(big);
        verify(transactions, times(2)).findUncategorizedRows(big);
    }

    @Test
    void groupTransactionsPagesFromTheCompactIdsNewestFirst() {
        UUID user = UUID.randomUUID();
        List<UncategorizedRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(datedRow("A", LocalDate.of(2026, 10, 1 + i)));
        }
        when(transactions.uncategorizedFingerprint(user)).thenReturn(fp(5, 1000));
        when(transactions.findUncategorizedRows(user)).thenReturn(rows);
        when(transactions.findGroupTransactionRows(any(), any())).thenAnswer(i -> List.of());
        String key = service.groupPage(user, PageRequest.of(0, 10)).getContent().get(0).groupKey();
        List<UUID> newestFirst = new java.util.ArrayList<>();
        for (int i = 4; i >= 0; i--) {
            newestFirst.add(rows.get(i).getId());
        }

        for (int[] p : new int[][] {{0, 0, 2}, {1, 2, 4}, {2, 4, 5}}) { // first, middle, last page of 2
            service.groupTransactions(user, key, PageRequest.of(p[0], 2));
        }

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(transactions, times(3)).findGroupTransactionRows(any(), captor.capture());
        assertEquals(newestFirst.subList(0, 2), List.copyOf(captor.getAllValues().get(0)));
        assertEquals(newestFirst.subList(2, 4), List.copyOf(captor.getAllValues().get(1)));
        assertEquals(newestFirst.subList(4, 5), List.copyOf(captor.getAllValues().get(2)));
    }

    private static UncategorizedRow datedRow(String text, LocalDate date) {
        UncategorizedRow base = row(text);
        return new UncategorizedRow() {
            public UUID getId() { return base.getId(); }
            public LocalDate getDate() { return date; }
            public String getSourcedDescription() { return text; }
            public String getDescription() { return null; }
            public TransactionType getType() { return TransactionType.DEBIT; }
            public BigDecimal getAmount() { return BigDecimal.TEN; }
            public String getMcc() { return null; }
        };
    }
}
