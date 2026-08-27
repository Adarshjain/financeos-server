package com.financeos.chat.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ChatAnswer(
        String answer,
        String clarify,
        List<String> clarifyOptions,
        JsonNode blocks,
        List<ChatTraceEntry> traces
) {
    public static ChatAnswer answer(String answer, List<ChatTraceEntry> traces) {
        return new ChatAnswer(answer, null, null, null, traces);
    }

    public static ChatAnswer answer(String answer, JsonNode blocks, List<ChatTraceEntry> traces) {
        return new ChatAnswer(answer, null, null, blocks, traces);
    }

    public static ChatAnswer clarify(String question, List<ChatTraceEntry> traces) {
        return new ChatAnswer(null, question, null, null, traces);
    }

    public static ChatAnswer clarify(String question, List<String> options, List<ChatTraceEntry> traces) {
        return new ChatAnswer(null, question, options, null, traces);
    }
}

