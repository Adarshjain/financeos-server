package com.financeos.chat.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record ChatToolResult(
        String toolName,
        boolean success,
        JsonNode result,
        String error
) {
    public static ChatToolResult success(String toolName, JsonNode result) {
        return new ChatToolResult(toolName, true, result, null);
    }

    public static ChatToolResult failure(String toolName, String error) {
        return new ChatToolResult(toolName, false, null, error);
    }
}
