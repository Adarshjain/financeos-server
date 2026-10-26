package com.financeos.chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.history.ChatConversation;
import com.financeos.chat.history.ChatHistoryService;
import com.financeos.chat.history.ChatTitleService;
import com.financeos.chat.orchestrator.ChatAnswer;
import com.financeos.chat.orchestrator.ChatEventSink;
import com.financeos.chat.orchestrator.ChatMessage;
import com.financeos.chat.orchestrator.ChatOrchestrator;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import com.financeos.core.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Runs one chat answer, stateless (the old chat page) or persisted (conversations). Transport-agnostic: events go
 * out through {@link EventOut}. Never logs questions, answers or titles.
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    static final String ERROR_MESSAGE = "Something went wrong while answering. Please try again.";

    @FunctionalInterface
    public interface EventOut {
        void send(String eventName, String jsonData) throws IOException;
    }

    public enum Outcome { DONE, ERROR, STOPPED }

    /** What {@link #prepare} resolved: the transcript to run and, when persisted, the conversation. */
    public record Prepared(boolean persisted, UUID conversationId, boolean newConversation, List<ChatMessage> transcript, UUID runId) {}

    private final ChatOrchestrator orchestrator;
    private final ChatHistoryService history;
    private final ChatTitleService titleService;
    private final ObjectMapper objectMapper;

    public ChatStreamService(ChatOrchestrator orchestrator, ChatHistoryService history,
                             ChatTitleService titleService, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.history = history;
        this.titleService = titleService;
        this.objectMapper = objectMapper;
    }

    /** Checks only (no writes): 404 for someone else's conversation, 400 when there is no question to answer. */
    public void validate(UUID userId, ChatStreamRequest request) {
        if (!request.isPersisted()) {
            return;
        }
        if (!request.isNewConversation()) {
            history.requireOwned(userId, request.conversationId());
        }
        if (request.isRetry()) {
            if (request.isNewConversation()) {
                throw new ValidationException("retry needs an existing conversationId");
            }
        } else if (lastUserQuestion(request) == null) {
            throw new ValidationException("A user question is required");
        }
    }

    /** Creates the conversation / stores the question / drops the replaced answer, and builds the transcript to run. */
    public Prepared prepare(UUID userId, ChatStreamRequest request) {
        if (!request.isPersisted()) {
            return new Prepared(false, null, false, request.messages(), null);
        }
        UUID conversationId;
        boolean created = request.isNewConversation();
        if (created) {
            conversationId = history.create(userId).getId();
        } else {
            conversationId = request.conversationId();
        }
        UUID runId = UUID.randomUUID();
        if (request.isRetry()) {
            if (history.prepareRetry(conversationId, runId) == null) {
                throw new ValidationException("The conversation has no question to retry");
            }
        } else {
            history.appendUserMessage(conversationId, lastUserQuestion(request), runId);
        }
        return new Prepared(true, conversationId, created, history.transcript(conversationId), runId);
    }

    public Outcome execute(UUID userId, Prepared prepared, EventOut out, BooleanSupplier disconnected) {
        long start = System.currentTimeMillis();
        List<ChatTraceEntry> traces = new ArrayList<>();
        ChatEventSink sink = new ChatEventSink() {
            @Override
            public void onStatus(String statusMessage) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("status", statusMessage);
                send(out, "status", node.toString());
            }

            @Override
            public void onTrace(ChatTraceEntry traceEntry) {
                traces.add(traceEntry);
                try {
                    send(out, "trace", objectMapper.writeValueAsString(traceEntry));
                } catch (IOException e) {
                    log.debug("Failed to serialize trace event: {}", e.getMessage());
                }
            }
        };

        boolean stored = false;
        try {
            if (prepared.newConversation()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("id", prepared.conversationId().toString());
                out.send("conversation", node.toString());
            }

            ChatAnswer answer = orchestrator.run(prepared.transcript(), sink);

            if (disconnected.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                Thread.interrupted();
                storeAssistant(prepared, null, traces, start, ChatHistoryService.STATUS_STOPPED, null);
                return Outcome.STOPPED;
            }

            UUID messageId = storeAssistant(prepared, answer, traces, start, ChatHistoryService.STATUS_COMPLETE, null);
            stored = true;
            long durationMs = System.currentTimeMillis() - start;

            ObjectNode finalNode = objectMapper.createObjectNode();
            if (answer.answer() != null) {
                finalNode.put("answer", answer.answer());
            }
            if (answer.clarify() != null) {
                finalNode.put("clarify", answer.clarify());
            }
            if (answer.clarifyOptions() != null && !answer.clarifyOptions().isEmpty()) {
                finalNode.set("clarifyOptions", objectMapper.valueToTree(answer.clarifyOptions()));
            }
            if (answer.blocks() != null) {
                finalNode.set("blocks", answer.blocks());
            }
            if (prepared.persisted()) {
                if (messageId != null) {
                    finalNode.put("messageId", messageId.toString());
                }
                finalNode.put("conversationId", prepared.conversationId().toString());
                finalNode.put("durationMs", durationMs);
            }
            out.send("final", finalNode.toString());

            if (prepared.persisted() && answer.answer() != null) {
                sendTitleIfNeeded(userId, prepared.conversationId(), answer.answer(), out);
            }

            log.info("Chat stream completed: userId={}, steps={}, durationMs={}", userId, answer.traces().size(), durationMs);
            return Outcome.DONE;
        } catch (Exception e) {
            boolean stopped = disconnected.getAsBoolean() || Thread.currentThread().isInterrupted()
                    || e instanceof InterruptedException;
            Thread.interrupted();
            if (stored) {
                // The answer is already saved; a later send failed (client gone). Never add a second message.
                log.debug("Chat stream ended after the answer was stored: {}", e.getClass().getSimpleName());
                return Outcome.STOPPED;
            }
            if (stopped) {
                storeAssistant(prepared, null, traces, start, ChatHistoryService.STATUS_STOPPED, null);
                return Outcome.STOPPED;
            }
            log.error("Error during chat streaming execution for user {}: {}", userId, e.getMessage(), e);
            UUID messageId = storeAssistant(prepared, null, traces, start, ChatHistoryService.STATUS_FAILED, ERROR_MESSAGE);
            try {
                ObjectNode errNode = objectMapper.createObjectNode();
                errNode.put("code", "EXECUTION_ERROR");
                errNode.put("message", ERROR_MESSAGE);
                if (prepared.persisted() && messageId != null) {
                    errNode.put("messageId", messageId.toString());
                }
                out.send("error", errNode.toString());
            } catch (Exception ignored) {}
            return Outcome.ERROR;
        }
    }

    private void sendTitleIfNeeded(UUID userId, UUID conversationId, String answerText, EventOut out) {
        try {
            if (!history.needsTitle(conversationId)) {
                return;
            }
            String title = titleService.generate(userId, history.firstQuestion(conversationId), answerText);
            if (history.setTitleIfNull(conversationId, title)) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("conversationId", conversationId.toString());
                node.put("title", title);
                out.send("title", node.toString());
            }
        } catch (Exception e) {
            log.warn("Chat title step failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Persists the assistant message in persisted mode; storage trouble never breaks the stream. */
    private UUID storeAssistant(Prepared prepared, ChatAnswer answer, List<ChatTraceEntry> traces, long start,
                                String status, String error) {
        if (!prepared.persisted()) {
            return null;
        }
        try {
            List<ChatTraceEntry> steps = answer != null && answer.traces() != null ? answer.traces() : traces;
            ObjectNode payload = history.buildPayload(answer, steps, System.currentTimeMillis() - start, status, error);
            return history.appendAssistantMessage(prepared.conversationId(), prepared.runId(), payload);
        } catch (Exception e) {
            log.warn("Could not store chat message: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static void send(EventOut out, String name, String json) {
        try {
            out.send(name, json);
        } catch (IOException e) {
            log.debug("Failed to stream {} event: {}", name, e.getMessage());
        }
    }

    private static String lastUserQuestion(ChatStreamRequest request) {
        List<ChatMessage> msgs = request.messages();
        if (msgs == null) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (m != null && "user".equalsIgnoreCase(m.role()) && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return null;
    }
}
