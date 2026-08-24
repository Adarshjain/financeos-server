package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.dto.RealizedLot;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class GetRealizedLotsTool implements ChatTool {

    private final InvestmentService investmentService;
    private final ObjectMapper objectMapper;

    public GetRealizedLotsTool(InvestmentService investmentService, ObjectMapper objectMapper) {
        this.investmentService = investmentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_realized_lots";
    }

    @Override
    public String description() {
        return "Fetch FIFO-matched realized gain/loss lots (delivery trades, corporate-action adjusted) for realized P&L and tax analysis.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("fromDate").put("type", "string").put("description", "Optional sell-date range start YYYY-MM-DD");
        props.putObject("toDate").put("type", "string").put("description", "Optional sell-date range end YYYY-MM-DD");
        ObjectNode instIds = props.putObject("instrumentIds");
        instIds.put("type", "array");
        instIds.putObject("items").put("type", "string");
        instIds.put("description", "Optional instrument UUID filter");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            LocalDate from = null;
            LocalDate to = null;
            Set<UUID> instrumentIds = new HashSet<>();
            if (args != null) {
                if (args.hasNonNull("fromDate") && !args.get("fromDate").asText().isBlank()) {
                    from = LocalDate.parse(args.get("fromDate").asText());
                }
                if (args.hasNonNull("toDate") && !args.get("toDate").asText().isBlank()) {
                    to = LocalDate.parse(args.get("toDate").asText());
                }
                if (args.has("instrumentIds") && args.get("instrumentIds").isArray()) {
                    for (JsonNode idNode : args.get("instrumentIds")) {
                        instrumentIds.add(UUID.fromString(idNode.asText()));
                    }
                }
            }

            final LocalDate fromF = from;
            final LocalDate toF = to;
            List<RealizedLot> lots = investmentService.getAllRealizedLots().stream()
                    .filter(l -> fromF == null || (l.sellDate() != null && !l.sellDate().isBefore(fromF)))
                    .filter(l -> toF == null || (l.sellDate() != null && !l.sellDate().isAfter(toF)))
                    .filter(l -> instrumentIds.isEmpty() || instrumentIds.contains(l.instrumentId()))
                    .toList();

            JsonNode resultNode = objectMapper.valueToTree(lots);
            return ChatToolResult.success(name(), resultNode);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to fetch realized lots: " + e.getMessage());
        }
    }
}
