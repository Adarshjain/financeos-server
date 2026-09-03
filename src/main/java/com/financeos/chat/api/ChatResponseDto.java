package com.financeos.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import org.springframework.lang.Nullable;
import java.util.List;

public record ChatResponseDto(
        @Nullable String answer,
        @Nullable String clarify,
        @Nullable List<String> clarifyOptions,
        @Nullable JsonNode blocks,
        List<ChatTraceEntry> traces
) {}

