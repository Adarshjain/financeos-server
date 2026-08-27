package com.financeos.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import java.util.List;

public record ChatResponseDto(
        String answer,
        String clarify,
        List<String> clarifyOptions,
        JsonNode blocks,
        List<ChatTraceEntry> traces
) {}

