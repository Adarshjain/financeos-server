package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingValuationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class GetPortfolioValueTool implements ChatTool {

    private final HoldingValuationService valuationService;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    public GetPortfolioValueTool(HoldingValuationService valuationService,
                                AccountRepository accountRepository,
                                ObjectMapper objectMapper) {
        this.valuationService = valuationService;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_portfolio_value";
    }

    @Override
    public String description() {
        return "Get corporate-action aware current total portfolio market value across all or specific broker accounts.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode brokerIds = props.putObject("brokerAccountIds");
        brokerIds.put("type", "array");
        brokerIds.putObject("items").put("type", "string");
        brokerIds.put("description", "Optional list of broker account UUIDs");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            UUID userId = UserContext.getCurrentUserId();

            // Tenancy: getBrokerMarketValue is not user-scoped, so NEVER pass a model-supplied
            // id to it directly — resolve the user's own broker accounts and intersect.
            List<UUID> ownBrokerIds = new ArrayList<>();
            for (Account acct : accountRepository.findByUserId(userId)) {
                if (acct.getType() == AccountType.broker) {
                    ownBrokerIds.add(acct.getId());
                }
            }

            List<UUID> targetBrokerIds = ownBrokerIds;
            if (args != null && args.has("brokerAccountIds") && args.get("brokerAccountIds").isArray() && args.get("brokerAccountIds").size() > 0) {
                List<UUID> requested = new ArrayList<>();
                for (JsonNode idNode : args.get("brokerAccountIds")) {
                    UUID id = UUID.fromString(idNode.asText());
                    if (ownBrokerIds.contains(id)) {
                        requested.add(id);
                    }
                }
                targetBrokerIds = requested;
            }

            BigDecimal totalMarketValue = BigDecimal.ZERO;
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode byBroker = response.putObject("byBroker");

            for (UUID brokerId : targetBrokerIds) {
                BigDecimal val = valuationService.getBrokerMarketValue(brokerId);
                totalMarketValue = totalMarketValue.add(val);
                byBroker.put(brokerId.toString(), val.setScale(2, RoundingMode.HALF_UP));
            }

            response.put("totalPortfolioValue", totalMarketValue.setScale(2, RoundingMode.HALF_UP));
            return ChatToolResult.success(name(), response);
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Failed to get portfolio value: " + e.getMessage());
        }
    }
}
