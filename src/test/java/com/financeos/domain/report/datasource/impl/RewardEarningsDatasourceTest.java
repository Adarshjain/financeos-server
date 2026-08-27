package com.financeos.domain.report.datasource.impl;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.reward.RuleStacking;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewardEarningsDatasourceTest {

    private RewardCalculationService rewardCalculationService;
    private AccountRepository accountRepository;
    private RewardRuleRepository rewardRuleRepository;
    private TransactionRepository transactionRepository;
    private RewardEarningsDatasource datasource;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        rewardCalculationService = mock(RewardCalculationService.class);
        accountRepository = mock(AccountRepository.class);
        rewardRuleRepository = mock(RewardRuleRepository.class);
        transactionRepository = mock(TransactionRepository.class);

        datasource = new RewardEarningsDatasource(
                rewardCalculationService,
                accountRepository,
                rewardRuleRepository,
                transactionRepository
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("reward_earnings", datasource.name());
        assertEquals("Reward Earnings", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "effectiveDate".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "valueInr".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "earned".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "earnedUnit".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "card".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "rule".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "reason".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "channel".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "mcc".equals(f.name())));
    }

    @Test
    void rowsEvaluationWithValuedPointsRupeesUnvaluedPointsAndNoRule() {
        UUID accountAId = UUID.randomUUID();
        Account accountA = new Account();
        accountA.setId(accountAId);
        accountA.setName("Infinia Card");
        accountA.setPointValueInr(new BigDecimal("0.25"));

        UUID accountBId = UUID.randomUUID();
        Account accountB = new Account();
        accountB.setId(accountBId);
        accountB.setName("Generic Points Card");
        accountB.setPointValueInr(null);

        UUID accountCId = UUID.randomUUID();
        Account accountC = new Account();
        accountC.setId(accountCId);
        accountC.setName("Unused Card");

        when(rewardRuleRepository.findDistinctAccountIdsByUserId(userId))
                .thenReturn(List.of(accountAId, accountBId, accountCId));

        when(accountRepository.findById(accountAId)).thenReturn(Optional.of(accountA));
        when(accountRepository.findById(accountBId)).thenReturn(Optional.of(accountB));
        when(accountRepository.findById(accountCId)).thenReturn(Optional.of(accountC));

        LocalDate minDateA = LocalDate.of(2025, 1, 1);
        LocalDate minDateB = LocalDate.of(2025, 2, 1);
        when(transactionRepository.findMinDateByAccountId(accountAId)).thenReturn(minDateA);
        when(transactionRepository.findMinDateByAccountId(accountBId)).thenReturn(minDateB);
        when(transactionRepository.findMinDateByAccountId(accountCId)).thenReturn(null); // No transactions -> skipped

        UUID txA1 = UUID.randomUUID();
        UUID txA2 = UUID.randomUUID();
        RewardLineResponse lineA1 = new RewardLineResponse(
                txA1, LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16),
                "Flight Booking", "AIR INDIA", "3000", TransactionChannel.ONLINE,
                new BigDecimal("10000.00"), new BigDecimal("10000.00"),
                UUID.randomUUID(), "SmartBuy 5X", RuleStacking.EXCLUSIVE, AccrualType.PERCENT,
                new BigDecimal("400"), "POINTS", RewardLineReason.MATCHED
        );
        RewardLineResponse lineA2 = new RewardLineResponse(
                txA2, LocalDate.of(2025, 1, 20), LocalDate.of(2025, 1, 20),
                "Grocery Store", "RELIANCE FRESH", "5411", TransactionChannel.POS,
                new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                UUID.randomUUID(), "Grocery 1%", RuleStacking.EXCLUSIVE, AccrualType.PERCENT,
                new BigDecimal("50.00"), "RUPEES", RewardLineReason.MATCHED
        );

        UUID txB1 = UUID.randomUUID();
        UUID txB2 = UUID.randomUUID();
        RewardLineResponse lineB1 = new RewardLineResponse(
                txB1, LocalDate.of(2025, 2, 10), LocalDate.of(2025, 2, 10),
                "Fuel Station", "HPCL", "5541", TransactionChannel.POS,
                new BigDecimal("2000.00"), new BigDecimal("2000.00"),
                UUID.randomUUID(), "Base Points", RuleStacking.EXCLUSIVE, AccrualType.SLAB,
                new BigDecimal("100"), "POINTS", RewardLineReason.MATCHED
        );
        RewardLineResponse lineB2 = new RewardLineResponse(
                txB2, LocalDate.of(2025, 2, 12), LocalDate.of(2025, 2, 12),
                "ATM Withdrawal", "CASH", "6011", TransactionChannel.OTHER,
                new BigDecimal("1000.00"), BigDecimal.ZERO,
                null, null, null, null,
                BigDecimal.ZERO, "RUPEES", RewardLineReason.NO_RULE
        );

        when(rewardCalculationService.lines(eq(accountAId), eq(minDateA), any(LocalDate.class), isNull()))
                .thenReturn(List.of(lineA1, lineA2));
        when(rewardCalculationService.lines(eq(accountBId), eq(minDateB), any(LocalDate.class), isNull()))
                .thenReturn(List.of(lineB1, lineB2));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(4, rows.size());

        // Row 1: Account A POINTS (400 pts * 0.25 = 100.00 INR)
        Map<String, Object> r1 = rows.get(0);
        assertEquals(txA1 + "_0", r1.get("id"));
        assertEquals("Infinia Card", r1.get("card"));
        assertEquals("SmartBuy 5X", r1.get("rule"));
        assertEquals("MATCHED", r1.get("reason"));
        assertEquals("POINTS", r1.get("earnedUnit"));
        assertEquals(new BigDecimal("400"), r1.get("earned"));
        assertEquals(new BigDecimal("100.00"), r1.get("valueInr"));
        assertEquals("ONLINE", r1.get("channel"));
        assertEquals("3000", r1.get("mcc"));

        // Row 2: Account A RUPEES (50.00 INR)
        Map<String, Object> r2 = rows.get(1);
        assertEquals(txA2 + "_1", r2.get("id"));
        assertEquals("Infinia Card", r2.get("card"));
        assertEquals("RUPEES", r2.get("earnedUnit"));
        assertEquals(new BigDecimal("50.00"), r2.get("earned"));
        assertEquals(new BigDecimal("50.00"), r2.get("valueInr"));

        // Row 3: Account B unvalued POINTS (pointValueInr is null -> valueInr 0.00)
        Map<String, Object> r3 = rows.get(2);
        assertEquals(txB1 + "_2", r3.get("id"));
        assertEquals("Generic Points Card", r3.get("card"));
        assertEquals("POINTS", r3.get("earnedUnit"));
        assertEquals(new BigDecimal("100"), r3.get("earned"));
        assertEquals(new BigDecimal("0.00"), r3.get("valueInr"));

        // Row 4: Account B NO_RULE (ruleName is null -> "(none)", earned 0, valueInr 0.00)
        Map<String, Object> r4 = rows.get(3);
        assertEquals(txB2 + "_3", r4.get("id"));
        assertEquals("Generic Points Card", r4.get("card"));
        assertEquals("(none)", r4.get("rule"));
        assertEquals("NO_RULE", r4.get("reason"));
        assertEquals(BigDecimal.ZERO, r4.get("earned"));
        assertEquals(new BigDecimal("0.00"), r4.get("valueInr"));

        // Assert all row IDs are unique across accounts
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            assertTrue(ids.add((String) row.get("id")), "Row IDs must be unique");
        }

        // Assert every catalog field appears as a row key in every row
        for (FieldDef fieldDef : datasource.fields()) {
            for (Map<String, Object> row : rows) {
                assertTrue(row.containsKey(fieldDef.name()), "Row should contain catalog field key: " + fieldDef.name());
            }
        }

        // Account C had null minDate -> verify rewardCalculationService was never called for accountCId
        verify(rewardCalculationService, never()).lines(eq(accountCId), any(), any(), any());
    }
}
