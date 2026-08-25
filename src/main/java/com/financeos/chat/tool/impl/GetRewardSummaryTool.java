package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardRuleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class GetRewardSummaryTool implements ChatTool {

    private final RewardCalculationService rewardService;
    private final AccountRepository accountRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final ObjectMapper objectMapper;

    public GetRewardSummaryTool(RewardCalculationService rewardService,
                                AccountRepository accountRepository,
                                RewardRuleRepository rewardRuleRepository,
                                ObjectMapper objectMapper) {
        this.rewardService = rewardService;
        this.accountRepository = accountRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_reward_summary";
    }

    @Override
    public String description() {
        return "Calculate credit card reward earnings, point breakdowns, cap status, and milestone progress for user accounts over a date range. Covers all user cards by default with dates defaulting to the current Indian Financial Year.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode accts = props.putObject("accountIds");
        accts.put("type", "array");
        accts.putObject("items").put("type", "string");
        accts.put("description", "Optional list of account UUIDs. Defaults to all user credit cards.");
        props.putObject("fromDate").put("type", "string").put("description", "Start date YYYY-MM-DD. Defaults to current Indian FY start (April 1).");
        props.putObject("toDate").put("type", "string").put("description", "End date YYYY-MM-DD. Defaults to today.");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            UUID currentUserId = UserContext.getCurrentUserId();
            List<Account> userAccounts = accountRepository.findByUserId(currentUserId);
            Map<UUID, Account> userAccountMap = userAccounts.stream()
                    .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));

            List<Account> candidateAccounts = new ArrayList<>();

            Set<UUID> requestedIds = new LinkedHashSet<>();
            if (args != null) {
                if (args.has("accountIds") && args.get("accountIds").isArray()) {
                    for (JsonNode item : args.get("accountIds")) {
                        try {
                            requestedIds.add(UUID.fromString(item.asText()));
                        } catch (Exception ignored) {}
                    }
                } else if (args.has("accountId") && !args.get("accountId").isNull()) {
                    try {
                        requestedIds.add(UUID.fromString(args.get("accountId").asText()));
                    } catch (Exception ignored) {}
                }
            }

            if (!requestedIds.isEmpty()) {
                // Intersect with user's own accounts; foreign IDs are silently dropped
                for (UUID id : requestedIds) {
                    Account acct = userAccountMap.get(id);
                    if (acct != null) {
                        candidateAccounts.add(acct);
                    }
                }
            } else {
                for (Account acct : userAccounts) {
                    if (acct.getType() == AccountType.credit_card || rewardRuleRepository.countByAccountId(acct.getId()) > 0) {
                        candidateAccounts.add(acct);
                    }
                }
            }

            if (candidateAccounts.isEmpty()) {
                ObjectNode emptyNode = objectMapper.createObjectNode();
                emptyNode.put("message", "No credit cards or reward-configured accounts found for user.");
                emptyNode.putArray("reports");
                return ChatToolResult.success(name(), emptyNode);
            }

            LocalDate today = LocalDate.now();
            LocalDate defaultFrom = today.getMonthValue() >= 4
                    ? LocalDate.of(today.getYear(), 4, 1)
                    : LocalDate.of(today.getYear() - 1, 4, 1);
            LocalDate defaultTo = today;

            LocalDate from = defaultFrom;
            LocalDate to = defaultTo;

            if (args != null && args.hasNonNull("fromDate") && !args.get("fromDate").asText().isBlank()) {
                try {
                    from = LocalDate.parse(args.get("fromDate").asText().trim());
                } catch (Exception ignored) {}
            }
            if (args != null && args.hasNonNull("toDate") && !args.get("toDate").asText().isBlank()) {
                try {
                    to = LocalDate.parse(args.get("toDate").asText().trim());
                } catch (Exception ignored) {}
            }

            ArrayNode resultArray = objectMapper.createArrayNode();
            for (Account account : candidateAccounts) {
                ObjectNode itemNode = objectMapper.createObjectNode();
                itemNode.put("accountId", account.getId().toString());
                itemNode.put("accountName", account.getName());
                try {
                    RewardReportResponse report = rewardService.report(account.getId(), from, to);
                    itemNode.set("report", objectMapper.valueToTree(report));
                } catch (Exception e) {
                    itemNode.put("error", e.getMessage() != null ? e.getMessage() : "Failed to calculate report");
                }
                resultArray.add(itemNode);
            }

            return ChatToolResult.success(name(), resultArray);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to get reward summary: " + e.getMessage());
        }
    }
}

