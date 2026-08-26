package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ListReportsTool implements ChatTool {

    private static final Logger log = LoggerFactory.getLogger(ListReportsTool.class);
    private static final int MAX_ROWS = 100;

    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    public ListReportsTool(ReportService reportService, ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "list_reports";
    }

    @Override
    public String description() {
        return "List the user's saved reports (id, name, type, datasource, description, updatedAt). Use ONLY when the user asks about existing reports or wants one updated/deleted — NOT before creating a new report.";
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
            List<Report> reports = reportService.list(null);
            ArrayNode arrayNode = objectMapper.createArrayNode();

            int count = 0;
            for (Report report : reports) {
                if (count >= MAX_ROWS) {
                    break;
                }
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", report.getId() != null ? report.getId().toString() : null);
                item.put("name", report.getName());
                item.put("type", report.getType() != null ? report.getType().name() : null);
                item.put("datasource", report.getDatasource());
                item.put("description", report.getDescription());
                item.put("updatedAt", report.getUpdatedAt() != null ? report.getUpdatedAt().toString() : null);
                arrayNode.add(item);
                count++;
            }

            return ChatToolResult.success(name(), arrayNode);
        } catch (Exception e) {
            log.warn("Unexpected error in list_reports", e);
            return ChatToolResult.failure(name(), "internal error");
        }
    }
}
