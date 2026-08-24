package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.investment.dto.SummaryResponse;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.investment.InvestmentService;
import org.springframework.stereotype.Component;

@Component
public class ComputeXirrTool implements ChatTool {

    private final InvestmentService investmentService;
    private final ObjectMapper objectMapper;

    public ComputeXirrTool(InvestmentService investmentService, ObjectMapper objectMapper) {
        this.investmentService = investmentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "compute_xirr";
    }

    @Override
    public String description() {
        return "Compute the PORTFOLIO-LEVEL investment summary including overall XIRR. Takes no arguments. For per-holding XIRR/returns, use get_positions instead.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            SummaryResponse summary = investmentService.getSummary();
            JsonNode resultNode = objectMapper.valueToTree(summary);
            return ChatToolResult.success(name(), resultNode);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to compute XIRR: " + e.getMessage());
        }
    }
}
