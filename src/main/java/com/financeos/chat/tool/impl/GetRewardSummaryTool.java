package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.reward.RewardCalculationService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
public class GetRewardSummaryTool implements ChatTool {

    private final RewardCalculationService rewardService;
    private final ObjectMapper objectMapper;

    public GetRewardSummaryTool(RewardCalculationService rewardService, ObjectMapper objectMapper) {
        this.rewardService = rewardService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_reward_summary";
    }

    @Override
    public String description() {
        return "Calculate credit card reward earnings, point breakdowns, cap status, and milestone progress for an account over a date range.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("accountId").put("type", "string").put("description", "Credit card or bank account UUID");
        props.putObject("fromDate").put("type", "string").put("description", "Start date YYYY-MM-DD");
        props.putObject("toDate").put("type", "string").put("description", "End date YYYY-MM-DD");
        schema.putArray("required").add("accountId").add("fromDate").add("toDate");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            if (args == null || !args.has("accountId") || !args.has("fromDate") || !args.has("toDate")) {
                return ChatToolResult.failure(name(), "accountId, fromDate, and toDate are required arguments");
            }

            UUID accountId = UUID.fromString(args.get("accountId").asText());
            LocalDate from = LocalDate.parse(args.get("fromDate").asText());
            LocalDate to = LocalDate.parse(args.get("toDate").asText());

            RewardReportResponse report = rewardService.report(accountId, from, to);
            JsonNode resultNode = objectMapper.valueToTree(report);
            return ChatToolResult.success(name(), resultNode);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to get reward summary: " + e.getMessage());
        }
    }
}
