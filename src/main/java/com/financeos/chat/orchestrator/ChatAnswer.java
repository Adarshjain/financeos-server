package com.financeos.chat.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ChatAnswer(
        String answer,
        String clarify,
        JsonNode blocks,
        List<ChatTraceEntry> traces
) {
    public static ChatAnswer answer(String answer, List<ChatTraceEntry> traces) {
        return new ChatAnswer(answer, null, null, traces);
    }

    public static ChatAnswer answer(String answer, JsonNode blocks, List<ChatTraceEntry> traces) {
        return new ChatAnswer(answer, null, blocks, traces);
    }

    public static ChatAnswer clarify(String question, List<ChatTraceEntry> traces) {
        return new ChatAnswer(null, question, null, traces);
    }
}

