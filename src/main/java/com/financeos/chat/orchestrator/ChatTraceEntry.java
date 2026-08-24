package com.financeos.chat.orchestrator;

public record ChatTraceEntry(
        int step,
        String action,
        String summary,
        String detail,
        Integer rowCount,
        Long durationMs
) {}
