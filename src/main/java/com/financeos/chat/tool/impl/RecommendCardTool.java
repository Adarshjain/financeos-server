package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.reward.dto.RewardRecommendationRequest;
import com.financeos.api.reward.dto.RewardRecommendationResponse;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.reward.RewardRecommendationService;
import com.financeos.domain.transaction.TransactionChannel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class RecommendCardTool implements ChatTool {

    private final RewardRecommendationService recommendationService;
    private final ObjectMapper objectMapper;

    public RecommendCardTool(RewardRecommendationService recommendationService, ObjectMapper objectMapper) {
        this.recommendationService = recommendationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "recommend_card";
    }

    @Override
    public String description() {
        return "Recommend the best credit card to use for a transaction based on expected reward rates and milestone progress.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("amount").put("type", "number").put("description", "Planned transaction amount in account currency");
        props.putObject("merchantText").put("type", "string").put("description", "Optional merchant description or name");
        props.putObject("mcc").put("type", "string").put("description", "Optional 4-digit Merchant Category Code");
        props.putObject("channel").put("type", "string").put("description", "Optional channel: POS, ONLINE, UPI, etc.");
        props.putObject("isEmi").put("type", "boolean").put("description", "Whether transaction is an EMI");
        props.putObject("isIntl").put("type", "boolean").put("description", "Whether transaction is international");
        props.putObject("date").put("type", "string").put("description", "Transaction date YYYY-MM-DD");
        props.putObject("cardId").put("type", "string").put("description", "Optional account card ID");
        schema.putArray("required").add("amount");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            if (args == null || !args.has("amount")) {
                return ChatToolResult.failure(name(), "amount is a required argument");
            }

            BigDecimal amount = new BigDecimal(args.get("amount").asText());
            String merchantText = args.has("merchantText") ? args.get("merchantText").asText() : null;
            String mcc = args.has("mcc") ? args.get("mcc").asText() : null;

            TransactionChannel channel = null;
            if (args.has("channel") && !args.get("channel").isNull()) {
                try {
                    channel = TransactionChannel.valueOf(args.get("channel").asText().toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }

            Boolean isEmi = args.has("isEmi") ? args.get("isEmi").asBoolean() : null;
            Boolean isIntl = args.has("isIntl") ? args.get("isIntl").asBoolean() : null;
            LocalDate date = args.has("date") ? LocalDate.parse(args.get("date").asText()) : LocalDate.now();

            List<UUID> categoryIds = new ArrayList<>();
            if (args.has("categoryIds") && args.get("categoryIds").isArray()) {
                for (JsonNode catNode : args.get("categoryIds")) {
                    categoryIds.add(UUID.fromString(catNode.asText()));
                }
            }

            Set<UUID> categorySet = categoryIds.isEmpty() ? null : new java.util.HashSet<>(categoryIds);
            UUID cardId = args.has("cardId") && !args.get("cardId").isNull() ? UUID.fromString(args.get("cardId").asText()) : null;

            RewardRecommendationRequest request = new RewardRecommendationRequest(
                    amount, date, categorySet, mcc, merchantText, channel, isEmi, isIntl, null, cardId
            );

            RewardRecommendationResponse response = recommendationService.recommend(request);
            JsonNode resultNode = objectMapper.valueToTree(response);
            return ChatToolResult.success(name(), resultNode);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to get card recommendation: " + e.getMessage());
        }
    }
}
