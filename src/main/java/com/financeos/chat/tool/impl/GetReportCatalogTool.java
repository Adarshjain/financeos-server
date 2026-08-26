package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GetReportCatalogTool implements ChatTool {

    private static final Logger log = LoggerFactory.getLogger(GetReportCatalogTool.class);

    private final DatasourceRegistry datasourceRegistry;
    private final ObjectMapper objectMapper;

    public GetReportCatalogTool(DatasourceRegistry datasourceRegistry, ObjectMapper objectMapper) {
        this.datasourceRegistry = datasourceRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_report_catalog";
    }

    @Override
    public String description() {
        return "Get the report-builder catalog: every datasource with its fields (role: measure/dimension/filter, type, allowed aggregations, enum values, which report types each field may appear in) and the filter operator catalog. Call this before drafting any report definition.";
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
            ObjectNode result = objectMapper.createObjectNode();
            result.set("catalog", objectMapper.valueToTree(datasourceRegistry.view()));
            result.put("note", "Fields with dynamic=true (category, account, …) take user-specific values — resolve actual names via the grounding block or v_chat_* queries.");
            return ChatToolResult.success(name(), result);
        } catch (Exception e) {
            log.warn("Unexpected error in get_report_catalog", e);
            return ChatToolResult.failure(name(), "internal error");
        }
    }
}
