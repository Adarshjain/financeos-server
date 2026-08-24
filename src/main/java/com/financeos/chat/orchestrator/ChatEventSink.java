package com.financeos.chat.orchestrator;

public interface ChatEventSink {
    void onStatus(String statusMessage);
    void onTrace(ChatTraceEntry traceEntry);

    ChatEventSink NOOP = new ChatEventSink() {
        @Override
        public void onStatus(String statusMessage) {}

        @Override
        public void onTrace(ChatTraceEntry traceEntry) {}
    };
}
