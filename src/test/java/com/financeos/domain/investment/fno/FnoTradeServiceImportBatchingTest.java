package com.financeos.domain.investment.fno;

import com.financeos.api.investment.dto.CommitFnoTradeDto;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The import reads the account's existing refs once; the answers are the per-trade exists-checks'. */
class FnoTradeServiceImportBatchingTest {

    private final UUID user = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> existing = new HashSet<>();
    private FnoTradeRepository repository;
    private FnoTradeService service;

    @BeforeEach
    void setUp() {
        repository = mock(FnoTradeRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        service = new FnoTradeService(repository, accounts, mock(UserRepository.class));
        Account account = new Account();
        account.setId(accountId);
        account.setType(AccountType.broker);
        when(accounts.findByIdAndUser_Id(eq(accountId), any())).thenReturn(Optional.of(account));
        when(repository.findExistingExternalRefs(eq(accountId), any())).thenAnswer(inv -> {
            java.util.Collection<String> refs = inv.getArgument(1);
            return refs.stream().filter(existing::contains).toList();
        });
        when(repository.save(any(FnoTrade.class))).thenAnswer(inv -> inv.getArgument(0));
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CommitFnoTradeDto trade(String ref, boolean skip) {
        return new CommitFnoTradeDto("NIFTYFUT", null, null, null, null, null, BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("110"), BigDecimal.ZERO, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 20), ref, skip);
    }

    @Test
    void existingSkippedAndRepeatedRefsAreNotSaved() {
        existing.add("OLD");
        List<FnoTrade> saved = service.importTrades(accountId, List.of(trade("OLD", false), trade("NEW", false),
                trade("NEW", false), trade("SKIPPED", true), trade(null, false), trade(" ", false)));

        assertEquals(3, saved.size());
        assertEquals(java.util.Arrays.asList("NEW", null, " "),
                saved.stream().map(FnoTrade::getExternalRef).toList());
        verify(repository, never()).existsByBrokerAccountIdAndExternalRef(any(), any());
    }

    @Test
    void readsDoNotGrowWithTheNumberOfTrades() {
        service.importTrades(accountId, rows(10));
        int small = reads();
        Mockito.clearInvocations(repository);
        existing.clear();

        List<FnoTrade> saved = service.importTrades(accountId, rows(1000));
        int large = reads();

        System.out.println("F&O import repository reads: 10 trades = " + small + ", 1000 trades = " + large);
        assertEquals(500, saved.size());
        // 1000 refs in 900-entry chunks: two queries; 10 refs: one.
        assertEquals(1, small);
        assertEquals(2, large);
    }

    private List<CommitFnoTradeDto> rows(int n) {
        List<CommitFnoTradeDto> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String ref = "R" + i;
            if (i % 2 == 0) existing.add(ref);
            rows.add(trade(ref, false));
        }
        return rows;
    }

    private int reads() {
        return (int) Mockito.mockingDetails(repository).getInvocations().stream()
                .filter(inv -> !inv.getMethod().getName().equals("save")).count();
    }
}
