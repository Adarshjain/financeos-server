package com.financeos.chat.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ChatTool {
    String name();
    String description();
    JsonNode argsSchema();
    ChatToolResult execute(JsonNode args);
}
