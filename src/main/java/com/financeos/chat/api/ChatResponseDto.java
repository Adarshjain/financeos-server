package com.financeos.chat.api;

import com.financeos.chat.orchestrator.ChatTraceEntry;
import java.util.List;

public record ChatResponseDto(
        String answer,
        String clarify,
        List<ChatTraceEntry> traces
) {}
