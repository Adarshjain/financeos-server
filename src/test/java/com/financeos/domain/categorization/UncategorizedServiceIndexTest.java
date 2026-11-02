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
}
