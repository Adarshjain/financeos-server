package com.financeos.chat.orchestrator;

import java.util.List;

public record ChatAnswer(
        String answer,
        String clarify,
        List<ChatTraceEntry> traces
) {
    public static ChatAnswer answer(String answer, List<ChatTraceEntry> traces) {
        return new ChatAnswer(answer, null, traces);
    }

    public static ChatAnswer clarify(String question, List<ChatTraceEntry> traces) {
        return new ChatAnswer(null, question, traces);
    }
}
