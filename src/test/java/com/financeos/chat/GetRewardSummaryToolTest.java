package com.financeos.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.chat.tool.impl.GetRewardSummaryTool;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GetRewardSummaryToolTest {

    private RewardCalculationService mockRewardService;
    private AccountRepository mockAccountRepository;
    private RewardRuleRepository mockRewardRuleRepository;
    private ObjectMapper objectMapper;
    private GetRewardSummaryTool tool;

    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        mockRewardService = mock(RewardCalculationService.class);
        mockAccountRepository = mock(AccountRepository.class);
        mockRewardRuleRepository = mock(RewardRuleRepository.class);
        objectMapper = new ObjectMapper();
        tool = new GetRewardSummaryTool(mockRewardService, mockAccountRepository, mockRewardRuleRepository, objectMapper);

        UserContext.setCurrentUserId(userId);
        user = new User();
        user.setId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Account createAccount(UUID id, String name, AccountType type) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setType(type);
        account.setUser(user);
        return account;
    }

    private RewardReportResponse createReport(UUID accountId, LocalDate from, LocalDate to) {
        RewardReportResponse.Summary summary = new RewardReportResponse.Summary(
                BigDecimal.valueOf(1000), 10, 8,
                BigDecimal.valueOf(50), BigDecimal.valueOf(200),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(50), BigDecimal.valueOf(5.0), BigDecimal.valueOf(5.0)
        );
        return new RewardReportResponse(summary, List.of(), List.of(), false, false);
    }

    @Test
    @DisplayName("Default all cards: user with 2 cards returns reports for both")
    void defaultAllCards() {
        UUID card1Id = UUID.randomUUID();
        UUID card2Id = UUID.randomUUID();
        Account card1 = createAccount(card1Id, "HDFC Infinia", AccountType.credit_card);
        Account card2 = createAccount(card2Id, "ICICI Amazon Pay", AccountType.credit_card);

        when(mockAccountRepository.findByUserId(userId)).thenReturn(List.of(card1, card2));
        when(mockRewardService.report(eq(card1Id), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createReport(card1Id, LocalDate.now(), LocalDate.now()));
        when(mockRewardService.report(eq(card2Id), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createReport(card2Id, LocalDate.now(), LocalDate.now()));

        ChatToolResult result = tool.execute(objectMapper.createObjectNode());

        assertTrue(result.success());
        JsonNode json = result.result();
        assertTrue(json.isArray());
        assertEquals(2, json.size());
        assertEquals("HDFC Infinia", json.get(0).get("accountName").asText());
        assertEquals("ICICI Amazon Pay", json.get(1).get("accountName").asText());
        assertTrue(json.get(0).has("report"));
        assertTrue(json.get(1).has("report"));
    }

    @Test
    @DisplayName("Foreign accountId is silently dropped, only owned accounts evaluated")
    void foreignAccountIdDropped() {
        UUID ownedCardId = UUID.randomUUID();
        UUID foreignCardId = UUID.randomUUID();
        Account ownedCard = createAccount(ownedCardId, "My Card", AccountType.credit_card);

        when(mockAccountRepository.findByUserId(userId)).thenReturn(List.of(ownedCard));
        when(mockRewardService.report(eq(ownedCardId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createReport(ownedCardId, LocalDate.now(), LocalDate.now()));

        JsonNode args = objectMapper.createObjectNode()
                .set("accountIds", objectMapper.createArrayNode()
                        .add(ownedCardId.toString())
                        .add(foreignCardId.toString()));

        ChatToolResult result = tool.execute(args);

        assertTrue(result.success());
        JsonNode json = result.result();
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        assertEquals(ownedCardId.toString(), json.get(0).get("accountId").asText());
        verify(mockRewardService, times(1)).report(eq(ownedCardId), any(), any());
        verify(mockRewardService, never()).report(eq(foreignCardId), any(), any());
    }

    @Test
    @DisplayName("One card throws exception: does not fail other cards, returns per-card error")
    void oneCardFailsGracefully() {
        UUID card1Id = UUID.randomUUID();
        UUID card2Id = UUID.randomUUID();
        Account card1 = createAccount(card1Id, "Failing Card", AccountType.credit_card);
        Account card2 = createAccount(card2Id, "Working Card", AccountType.credit_card);

        when(mockAccountRepository.findByUserId(userId)).thenReturn(List.of(card1, card2));
        when(mockRewardService.report(eq(card1Id), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("Calculation timeout"));
        when(mockRewardService.report(eq(card2Id), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createReport(card2Id, LocalDate.now(), LocalDate.now()));

        ChatToolResult result = tool.execute(objectMapper.createObjectNode());

        assertTrue(result.success());
        JsonNode json = result.result();
        assertTrue(json.isArray());
        assertEquals(2, json.size());

        // Card 1 failed
        assertEquals("Failing Card", json.get(0).get("accountName").asText());
        assertTrue(json.get(0).has("error"));
        assertEquals("Calculation timeout", json.get(0).get("error").asText());

        // Card 2 succeeded
        assertEquals("Working Card", json.get(1).get("accountName").asText());
        assertTrue(json.get(1).has("report"));
    }

    @Test
    @DisplayName("Explicit dates are passed through to rewardService")
    void explicitDatesPassedThrough() {
        UUID cardId = UUID.randomUUID();
        Account card = createAccount(cardId, "Axis Magnus", AccountType.credit_card);

        when(mockAccountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(mockRewardService.report(eq(cardId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createReport(cardId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));

        JsonNode args = objectMapper.createObjectNode()
                .put("fromDate", "2026-01-01")
                .put("toDate", "2026-06-30");

        ChatToolResult result = tool.execute(args);

        assertTrue(result.success());
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(mockRewardService).report(eq(cardId), fromCaptor.capture(), toCaptor.capture());

        assertEquals(LocalDate.of(2026, 1, 1), fromCaptor.getValue());
        assertEquals(LocalDate.of(2026, 6, 30), toCaptor.getValue());
    }

    @Test
    @DisplayName("User with no cards returns success with explanatory message")
    void noCardsReturnsSuccess() {
        when(mockAccountRepository.findByUserId(userId)).thenReturn(List.of());

        ChatToolResult result = tool.execute(objectMapper.createObjectNode());

        assertTrue(result.success());
        JsonNode json = result.result();
        assertTrue(json.has("message"));
        assertTrue(json.get("message").asText().contains("No credit cards"));
    }
}
