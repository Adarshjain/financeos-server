package com.financeos.domain.report.datasource.impl;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCalculationService.ReportLine;
import com.financeos.domain.reward.RewardLineReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A reward line's description is the text its transaction shows: the transaction's own
 * description, else the one it was imported with.
 */
class RewardEarningsDatasourceDescriptionTest {

    private RewardCalculationService rewards;
    private RewardReportSupport support;
    private RewardEarningsDatasource datasource;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        rewards = mock(RewardCalculationService.class);
        support = mock(RewardReportSupport.class);
        datasource = new RewardEarningsDatasource(rewards, support);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void descriptionIsTheOwnTextElseTheImportedTextElseNull() {
        Account card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("Card");
        when(support.ruleAccounts(userId)).thenReturn(List.of(card));
        when(support.ruleLabels(List.of(card))).thenReturn(Map.of());
        when(support.bounds(card.getId())).thenReturn(
                new RewardReportSupport.DateBounds(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)));
        when(rewards.reportLines(eq(card.getId()), any(), any())).thenReturn(List.of(
                reportLine("Flight Booking", "AIR INDIA"),
                reportLine(null, "UPI/SWIGGY/1234"),
                reportLine(null, null)));

        List<Object> descriptions = new ArrayList<>();
        datasource.rows().forEach(r -> descriptions.add(r.get("description")));

        assertEquals("Flight Booking", descriptions.get(0));
        assertEquals("UPI/SWIGGY/1234", descriptions.get(1));
        assertNull(descriptions.get(2));
    }

    private static ReportLine reportLine(String description, String sourcedDescription) {
        RewardLineResponse line = new RewardLineResponse(UUID.randomUUID(), LocalDate.of(2025, 1, 15),
                LocalDate.of(2025, 1, 15), description, sourcedDescription, null, null,
                new BigDecimal("100"), new BigDecimal("100"), null, null, null, null,
                BigDecimal.ZERO, "RUPEES", RewardLineReason.NO_RULE);
        return new ReportLine(line, true, true, new BigDecimal("100"), null, null, List.of(),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), LocalDate.of(2024, 6, 1), LocalDate.of(2025, 5, 31));
    }
}
