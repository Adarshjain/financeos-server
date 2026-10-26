package com.financeos.chat.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.history.ChatHistoryDtos.ConversationDetail;
import com.financeos.chat.history.ChatHistoryDtos.ConversationSummary;
import com.financeos.chat.history.ChatHistoryDtos.MessageView;
import com.financeos.chat.orchestrator.ChatAnswer;
import com.financeos.chat.orchestrator.ChatMessage;
import com.financeos.chat.orchestrator.ChatStepLabels;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted chat conversations. Every entry point takes the owning user and 404s on anyone else's data. */
@Service
public class ChatHistoryService {

    public static final int MAX_CONVERSATIONS = 10;
    public static final int MAX_DRAFT_RESULT_CHARS = 2000;

    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_STOPPED = "STOPPED";

    private static final String CLARIFY_PREFIX = "[You asked the user to clarify]: ";

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(ChatConversationRepository conversations, ChatMessageRepository messages,
                              ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    /** Creates a conversation, then deletes the user's conversations beyond the newest {@link #MAX_CONVERSATIONS}. */
    @Transactional
    public ChatConversation create(UUID userId) {
        ChatConversation c = new ChatConversation();
        c.setUserId(userId);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c = conversations.saveAndFlush(c);
        List<UUID> ids = conversations.findIdsNewestFirst(userId);
        if (ids.size() > MAX_CONVERSATIONS) {
            conversations.deleteByUserAndIds(userId, new ArrayList<>(ids.subList(MAX_CONVERSATIONS, ids.size())));
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(UUID userId) {
        List<ConversationSummary> out = new ArrayList<>();
        for (Object[] row : conversations.findSummaries(userId, PageRequest.of(0, MAX_CONVERSATIONS))) {
            out.add(new ConversationSummary((UUID) row[0], (String) row[1], toInstant(row[2]), toInstant(row[3]),
                    ((Number) row[4]).longValue()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ConversationDetail get(UUID userId, UUID id) {
        ChatConversation c = requireOwned(userId, id);
        List<MessageView> views = new ArrayList<>();
        for (ChatMessageRecord m : messages.findByConversationIdOrderBySeqAsc(id)) {
            boolean user = ChatMessageRecord.USER.equals(m.getRole());
            views.add(new MessageView(m.getId(), m.getRole(), m.getCreatedAt(),
                    user ? m.getContent() : null,
                    user ? null : parse(m.getContent()),
                    parse(m.getDraftResult())));
        }
        return new ConversationDetail(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(), views);
    }

    @Transactional
    public ConversationSummary rename(UUID userId, UUID id, String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty() || trimmed.length() > ChatConversation.MAX_TITLE_LENGTH) {
            throw new ValidationException("Title must be 1 to " + ChatConversation.MAX_TITLE_LENGTH + " characters");
        }
        ChatConversation c = requireOwned(userId, id);
        c.setTitle(trimmed);
        conversations.save(c);
        return new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                messages.countByConversationIdAndRole(id, ChatMessageRecord.USER));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        ChatConversation c = requireOwned(userId, id);
        conversations.deleteByUserAndIds(userId, List.of(c.getId()));
    }

    @Transactional
    public void setDraftResult(UUID userId, UUID conversationId, UUID messageId, JsonNode draftResult) {
        requireOwned(userId, conversationId);
        if (draftResult == null || !draftResult.isObject()) {
            throw new ValidationException("draftResult must be a JSON object");
        }
        String json = draftResult.toString();
        if (json.length() > MAX_DRAFT_RESULT_CHARS) {
            throw new ValidationException("draftResult must be at most " + MAX_DRAFT_RESULT_CHARS + " characters");
        }
        ChatMessageRecord m = messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat message", messageId));
        if (!ChatMessageRecord.ASSISTANT.equals(m.getRole())) {
            throw new ValidationException("draftResult can only be stored on an assistant message");
        }
        m.setDraftResult(json);
        messages.save(m);
    }

    @Transactional(readOnly = true)
    public ChatConversation requireOwned(UUID userId, UUID id) {
        return conversations.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat conversation", id));
    }

    @Transactional
    public UUID appendUserMessage(UUID conversationId, String text, UUID runId) {
        conversations.lockById(conversationId).ifPresent(c -> c.setActiveRunId(runId));
        ChatMessageRecord m = newMessage(conversationId, ChatMessageRecord.USER, text);
        conversations.touch(conversationId, Instant.now());
        return m.getId();
    }

    /** Stores the assistant payload (null when a newer run owns the conversation) and bumps updated_at. */
    @Transactional
    public UUID appendAssistantMessage(UUID conversationId, UUID runId, ObjectNode payload) {
        boolean current = conversations.lockById(conversationId)
                .map(c -> runId != null && runId.equals(c.getActiveRunId())).orElse(false);
        if (!current) {
            // A newer run (send or retry) owns the conversation now: this stale worker's write is dropped.
            return null;
        }
        ChatMessageRecord m = newMessage(conversationId, ChatMessageRecord.ASSISTANT, payload.toString());
        conversations.touch(conversationId, Instant.now());
        return m.getId();
    }

    /** Retry: drops the assistant messages after the last USER message and returns that question; null when there is none. */
    @Transactional
    public String prepareRetry(UUID conversationId, UUID runId) {
        conversations.lockById(conversationId).ifPresent(c -> c.setActiveRunId(runId));
        List<ChatMessageRecord> all = messages.findByConversationIdOrderBySeqAsc(conversationId);
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessageRecord m = all.get(i);
            if (ChatMessageRecord.USER.equals(m.getRole())) {
                messages.deleteAfterSeq(conversationId, m.getSeq());
                conversations.touch(conversationId, Instant.now());
                return m.getContent();
            }
        }
        return null;
    }

    /** The conversation as the model should see it: stored questions and answers (failed/stopped replies are skipped). */
    @Transactional(readOnly = true)
    public List<ChatMessage> transcript(UUID conversationId) {
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessageRecord m : messages.findByConversationIdOrderBySeqAsc(conversationId)) {
            if (ChatMessageRecord.USER.equals(m.getRole())) {
                out.add(new ChatMessage("user", m.getContent()));
                continue;
            }
            JsonNode p = parse(m.getContent());
            if (p == null || !STATUS_COMPLETE.equals(p.path("status").asText())) {
                continue;
            }
            if (p.hasNonNull("answer")) {
                out.add(new ChatMessage("assistant", p.get("answer").asText()));
            } else if (p.hasNonNull("clarify")) {
                out.add(new ChatMessage("assistant", CLARIFY_PREFIX + p.get("clarify").asText()));
            }
        }
        return out;
    }

    /** Whether the conversation still has no title. */
    @Transactional(readOnly = true)
    public boolean needsTitle(UUID conversationId) {
        return conversations.findById(conversationId).map(c -> c.getTitle() == null).orElse(false);
    }

    @Transactional(readOnly = true)
    public String firstQuestion(UUID conversationId) {
        for (ChatMessageRecord m : messages.findByConversationIdOrderBySeqAsc(conversationId)) {
            if (ChatMessageRecord.USER.equals(m.getRole())) {
                return m.getContent();
            }
        }
        return null;
    }

    /** Saves the title only while the conversation has none; returns whether it was saved. */
    @Transactional
    public boolean setTitleIfNull(UUID conversationId, String title) {
        return conversations.setTitleIfNull(conversationId, title) > 0;
    }

    /** The stored assistant payload: answer/clarify/blocks, steps, durationMs, status and an optional error. */
    public ObjectNode buildPayload(ChatAnswer answer, List<ChatTraceEntry> traces, long durationMs, String status, String error) {
        ObjectNode n = objectMapper.createObjectNode();
        if (answer != null) {
            if (answer.answer() != null) {
                n.put("answer", answer.answer());
            }
            if (answer.clarify() != null) {
                n.put("clarify", answer.clarify());
            }
            if (answer.clarifyOptions() != null && !answer.clarifyOptions().isEmpty()) {
                n.set("clarifyOptions", objectMapper.valueToTree(answer.clarifyOptions()));
            }
            if (answer.blocks() != null) {
                n.set("blocks", answer.blocks());
            }
        }
        ArrayNode steps = n.putArray("steps");
        if (traces != null) {
            for (ChatTraceEntry t : traces) {
                ObjectNode s = steps.addObject();
                s.put("action", t.action());
                s.put("label", t.label() != null ? t.label() : ChatStepLabels.labelFor(t.action(), null));
                s.put("summary", t.summary());
                s.put("detail", t.detail());
                if (t.rowCount() != null) {
                    s.put("rowCount", t.rowCount());
                } else {
                    s.putNull("rowCount");
                }
                if (t.durationMs() != null) {
                    s.put("durationMs", t.durationMs());
                } else {
                    s.putNull("durationMs");
                }
                s.put("success", t.success());
                if (t.error() != null) {
                    s.put("error", t.error());
                } else {
                    s.putNull("error");
                }
            }
        }
        n.put("durationMs", durationMs);
        n.put("status", status);
        if (error != null) {
            n.put("error", error);
        }
        return n;
    }

    private ChatMessageRecord newMessage(UUID conversationId, String role, String content) {
        ChatMessageRecord m = new ChatMessageRecord();
        m.setConversationId(conversationId);
        m.setSeq(messages.maxSeq(conversationId) + 1);
        m.setRole(role);
        m.setContent(content);
        return messages.save(m);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return null;
    }
}
