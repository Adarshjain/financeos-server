package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.report.dto.ReportResponse;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GetReportTool implements ChatTool {

    private static final Logger log = LoggerFactory.getLogger(GetReportTool.class);

    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    public GetReportTool(ReportService reportService, ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_report";
    }

    @Override
    public String description() {
        return "Fetch one saved report including its full definition JSON. Needed before proposing an update.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode reportId = props.putObject("reportId");
        reportId.put("type", "string");
        reportId.put("description", "UUID of the saved report from list_reports");
        ArrayNode req = schema.putArray("required");
        req.add("reportId");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            if (args == null || !args.hasNonNull("reportId")) {
                return ChatToolResult.failure(name(), "reportId is required");
            }
            String reportIdText = args.get("reportId").asText().trim();
            UUID uuid;
            try {
                uuid = UUID.fromString(reportIdText);
            } catch (IllegalArgumentException e) {
                return ChatToolResult.failure(name(), "reportId must be a UUID from list_reports");
            }

            Report report = reportService.get(uuid);
            ReportResponse response = ReportResponse.from(report, objectMapper);
            return ChatToolResult.success(name(), objectMapper.valueToTree(response));
        } catch (ValidationException | ResourceNotFoundException | IllegalArgumentException e) {
            return ChatToolResult.failure(name(), e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error in get_report", e);
            return ChatToolResult.failure(name(), "internal error");
        }
    }
}
