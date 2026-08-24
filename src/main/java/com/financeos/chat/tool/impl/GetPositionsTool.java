package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.investment.dto.PositionDto;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.investment.InvestmentService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class GetPositionsTool implements ChatTool {

    private final InvestmentService investmentService;
    private final ObjectMapper objectMapper;

    public GetPositionsTool(InvestmentService investmentService, ObjectMapper objectMapper) {
        this.investmentService = investmentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_positions";
    }

    @Override
    public String description() {
        return "Fetch computed investment positions with cost basis, current market value, unrealized gain, and P&L per holding.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode brokerIds = props.putObject("brokerAccountIds");
        brokerIds.put("type", "array");
        brokerIds.putObject("items").put("type", "string");
        brokerIds.put("description", "Optional list of broker account UUIDs to filter by");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            Set<UUID> filterBrokerIds = new HashSet<>();
            if (args != null && args.has("brokerAccountIds") && args.get("brokerAccountIds").isArray()) {
                for (JsonNode idNode : args.get("brokerAccountIds")) {
                    filterBrokerIds.add(UUID.fromString(idNode.asText()));
                }
            }

            List<PositionDto> allPositions = investmentService.getAllPositions();
            List<PositionDto> filtered = allPositions;
            if (!filterBrokerIds.isEmpty()) {
                filtered = allPositions.stream()
                        .filter(p -> filterBrokerIds.contains(p.brokerAccountId()))
                        .toList();
            }

            JsonNode resultNode = objectMapper.valueToTree(filtered);
            return ChatToolResult.success(name(), resultNode);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to fetch positions: " + e.getMessage());
        }
    }
}
