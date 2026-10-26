package com.financeos.chat.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.history.ChatHistoryDtos.ConversationDetail;
import com.financeos.chat.history.ChatHistoryDtos.ConversationSummary;
import com.financeos.chat.orchestrator.ChatAnswer;
import com.financeos.chat.orchestrator.ChatMessage;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class ChatHistoryServiceTest {

    private final ChatConversationRepository conversations = mock(ChatConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ChatHistoryService service = new ChatHistoryService(conversations, messages, mapper);
    private final UUID userId = UUID.randomUUID();

    private ChatConversation conv(UUID id) {
        ChatConversation c = new ChatConversation();
        c.setId(id);
        c.setUserId(userId);
        c.setCreatedAt(Instant.parse("2026-10-26T01:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-10-26T02:00:00Z"));
        return c;
    }

    private ChatMessageRecord msg(UUID convId, int seq, String role, String content) {
        ChatMessageRecord m = new ChatMessageRecord();
        m.setId(UUID.randomUUID());
        m.setConversationId(convId);
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(Instant.parse("2026-10-26T01:00:00Z").plusSeconds(seq));
        return m;
    }

    private List<UUID> ids(int n) {
        List<UUID> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(UUID.randomUUID());
        return l;
    }

    @Test
    void createEvictsConversationsBeyondTheNewestTen() {
        when(conversations.saveAndFlush(any())).thenAnswer(i -> {
            ChatConversation c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        List<UUID> all = ids(12);
        when(conversations.findIdsNewestFirst(userId)).thenReturn(all);

        ChatConversation created = service.create(userId);

        assertEquals(userId, created.getUserId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> evicted = ArgumentCaptor.forClass(List.class);
        verify(conversations).deleteByUserAndIds(eq(userId), evicted.capture());
        assertEquals(all.subList(10, 12), evicted.getValue());
    }

    @Test
    void createKeepsExactlyTenWithoutEvicting() {
        when(conversations.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(conversations.findIdsNewestFirst(userId)).thenReturn(ids(10));
        service.create(userId);
        verify(conversations, never()).deleteByUserAndIds(any(), anyList());
    }

    @Test
    void listMapsRowsInQueryOrderWithCountsAndAsksForTen() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant t = Instant.parse("2026-10-26T02:00:00Z");
        when(conversations.findSummaries(eq(userId), any(Pageable.class))).thenReturn(List.of(
                new Object[] {a, "First", t, t, 3L},
                new Object[] {b, null, t.minusSeconds(60), t.minusSeconds(30), 1}));

        List<ConversationSummary> out = service.list(userId);

        assertEquals(List.of(a, b), out.stream().map(ConversationSummary::id).toList());
        assertEquals(3L, out.get(0).questionCount());
        assertEquals(1L, out.get(1).questionCount());
        assertNull(out.get(1).title());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(conversations).findSummaries(eq(userId), page.capture());
        assertEquals(10, page.getValue().getPageSize());
        verify(messages, never()).countByConversationIdAndRole(any(), any());
    }

    @Test
    void getReturnsMessagesInSeqOrderWithParsedAnswerAndDraftResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.of(conv(id)));
        ChatMessageRecord u = msg(id, 1, "USER", "How much did I spend?");
        ChatMessageRecord a = msg(id, 2, "ASSISTANT", "{\"answer\":\"42\",\"steps\":[],\"status\":\"COMPLETE\"}");
        a.setDraftResult("{\"reportId\":\"r1\",\"action\":\"saved\"}");
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of(u, a));

        ConversationDetail d = service.get(userId, id);

        assertEquals(2, d.messages().size());
        assertEquals("USER", d.messages().get(0).role());
        assertEquals("How much did I spend?", d.messages().get(0).text());
        assertNull(d.messages().get(0).answer());
        assertNull(d.messages().get(0).draftResult());
        assertEquals("42", d.messages().get(1).answer().get("answer").asText());
        assertEquals("saved", d.messages().get(1).draftResult().get("action").asText());
        assertNull(d.messages().get(1).text());
        // serialised: draftResult is always present, text only for user messages
        JsonNode json = mapper.readTree(mapper.writeValueAsString(d));
        assertTrue(json.get("messages").get(0).has("draftResult"));
        assertFalse(json.get("messages").get(0).has("answer"));
    }

    @Test
    void anotherUsersConversationIs404ForEveryOperation() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.empty());
        UUID msgId = UUID.randomUUID();
        ObjectNode draft = mapper.createObjectNode();
        assertThrows(ResourceNotFoundException.class, () -> service.get(userId, id));
        assertThrows(ResourceNotFoundException.class, () -> service.rename(userId, id, "x"));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(userId, id));
        assertThrows(ResourceNotFoundException.class, () -> service.setDraftResult(userId, id, msgId, draft));
        verify(conversations, never()).deleteByUserAndIds(any(), anyList());
    }

    @Test
    void renameTrimsAndValidatesLength() {
        UUID id = UUID.randomUUID();
        ChatConversation c = conv(id);
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.of(c));
        when(messages.countByConversationIdAndRole(id, "USER")).thenReturn(2L);

        ConversationSummary s = service.rename(userId, id, "  Spending last month  ");
        assertEquals("Spending last month", s.title());
        assertEquals(2L, s.questionCount());

        assertThrows(ValidationException.class, () -> service.rename(userId, id, "   "));
        assertThrows(ValidationException.class, () -> service.rename(userId, id, null));
        assertThrows(ValidationException.class, () -> service.rename(userId, id, "x".repeat(121)));
        assertEquals("x".repeat(120), service.rename(userId, id, "x".repeat(120)).title());
    }

    @Test
    void deleteRemovesTheOwnedConversation() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.of(conv(id)));
        service.delete(userId, id);
        verify(conversations).deleteByUserAndIds(userId, List.of(id));
    }

    @Test
    void draftResultIsStoredOnAssistantMessagesOnly() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.of(conv(id)));
        ChatMessageRecord assistant = msg(id, 2, "ASSISTANT", "{}");
        ChatMessageRecord user = msg(id, 1, "USER", "q");
        when(messages.findByIdAndConversationId(assistant.getId(), id)).thenReturn(java.util.Optional.of(assistant));
        when(messages.findByIdAndConversationId(user.getId(), id)).thenReturn(java.util.Optional.of(user));
        ObjectNode draft = mapper.createObjectNode().put("reportId", "r1").put("addedToHome", true);

        service.setDraftResult(userId, id, assistant.getId(), draft);
        assertEquals(draft.toString(), assistant.getDraftResult());

        assertThrows(ValidationException.class, () -> service.setDraftResult(userId, id, user.getId(), draft));
        assertNull(user.getDraftResult());
        assertThrows(ResourceNotFoundException.class,
                () -> service.setDraftResult(userId, id, UUID.randomUUID(), draft));
    }

    @Test
    void draftResultMustBeASmallObject() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, userId)).thenReturn(java.util.Optional.of(conv(id)));
        ObjectNode big = mapper.createObjectNode().put("note", "x".repeat(2000));
        assertThrows(ValidationException.class, () -> service.setDraftResult(userId, id, UUID.randomUUID(), big));
        assertThrows(ValidationException.class,
                () -> service.setDraftResult(userId, id, UUID.randomUUID(), mapper.createArrayNode()));
        assertThrows(ValidationException.class, () -> service.setDraftResult(userId, id, UUID.randomUUID(), null));
    }

    @Test
    void transcriptIsBuiltFromStoredMessagesSkippingFailedAndStoppedReplies() {
        UUID id = UUID.randomUUID();
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of(
                msg(id, 1, "USER", "q1"),
                msg(id, 2, "ASSISTANT", "{\"clarify\":\"Which card?\",\"status\":\"COMPLETE\"}"),
                msg(id, 3, "USER", "HDFC"),
                msg(id, 4, "ASSISTANT", "{\"answer\":\"a2\",\"status\":\"COMPLETE\"}"),
                msg(id, 5, "USER", "q3"),
                msg(id, 6, "ASSISTANT", "{\"status\":\"FAILED\",\"error\":\"boom\"}"),
                msg(id, 7, "USER", "q4")));

        List<ChatMessage> t = service.transcript(id);

        assertEquals(List.of(
                new ChatMessage("user", "q1"),
                new ChatMessage("assistant", "[You asked the user to clarify]: Which card?"),
                new ChatMessage("user", "HDFC"),
                new ChatMessage("assistant", "a2"),
                new ChatMessage("user", "q3"),
                new ChatMessage("user", "q4")), t);
    }

    @Test
    void retryDropsTheAssistantMessagesAfterTheLastQuestion() {
        UUID id = UUID.randomUUID();
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of(
                msg(id, 1, "USER", "q1"), msg(id, 2, "ASSISTANT", "{}"),
                msg(id, 3, "USER", "q2"), msg(id, 4, "ASSISTANT", "{}")));

        assertEquals("q2", service.prepareRetry(id, UUID.randomUUID()));
        verify(messages).deleteAfterSeq(id, 3);
        verify(messages, never()).save(any());
    }

    @Test
    void retryWithoutAQuestionReturnsNull() {
        UUID id = UUID.randomUUID();
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of());
        assertNull(service.prepareRetry(id, UUID.randomUUID()));
    }

    @Test
    void appendNumbersMessagesAfterTheCurrentMax() {
        UUID id = UUID.randomUUID();
        when(messages.maxSeq(id)).thenReturn(4);
        when(messages.save(any())).thenAnswer(i -> {
            ChatMessageRecord m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        service.appendUserMessage(id, "q", UUID.randomUUID());
        ArgumentCaptor<ChatMessageRecord> saved = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(messages).save(saved.capture());
        assertEquals(5, saved.getValue().getSeq());
        assertEquals("USER", saved.getValue().getRole());
        verify(conversations).touch(eq(id), any(Instant.class));
    }

    @Test
    void payloadCarriesAnswerStepsLabelsAndStatus() {
        ChatTraceEntry sql = new ChatTraceEntry(1, "run_sql", "Executed SQL query", "select 1", 3, 12L, true, null, "preview", "Queried your data");
        ChatAnswer answer = new ChatAnswer("Hello", null, null, null, List.of(sql));

        ObjectNode p = service.buildPayload(answer, answer.traces(), 99L, "COMPLETE", null);

        assertEquals("Hello", p.get("answer").asText());
        assertFalse(p.has("clarify"));
        assertFalse(p.has("error"));
        assertEquals("COMPLETE", p.get("status").asText());
        assertEquals(99L, p.get("durationMs").asLong());
        JsonNode step = p.get("steps").get(0);
        assertEquals("Queried your data", step.get("label").asText());
        assertEquals("run_sql", step.get("action").asText());
        assertEquals(3, step.get("rowCount").asInt());
        assertTrue(step.get("success").asBoolean());
        assertFalse(step.has("resultPreview"));
    }

    @Test
    void failedPayloadKeepsStepsSoFarAndTheError() {
        ChatTraceEntry t = new ChatTraceEntry(1, "call_tool", "s", "{}", null, 5L, false, "bad", null, "Did the maths");
        ObjectNode p = service.buildPayload(null, List.of(t), 7L, "FAILED", "Something went wrong");
        assertEquals("FAILED", p.get("status").asText());
        assertEquals("Something went wrong", p.get("error").asText());
        assertEquals(1, p.get("steps").size());
        assertFalse(p.has("answer"));
    }

    @Test
    void staleRunsAssistantMessageIsDropped() {
        UUID id = UUID.randomUUID();
        ChatConversation c = conv(id);
        c.setActiveRunId(UUID.randomUUID());
        when(conversations.lockById(id)).thenReturn(java.util.Optional.of(c));

        assertNull(service.appendAssistantMessage(id, UUID.randomUUID(), mapper.createObjectNode()));
        verify(messages, never()).save(any());
    }

    @Test
    void currentRunsAssistantMessageIsStored() {
        UUID id = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        ChatConversation c = conv(id);
        c.setActiveRunId(run);
        when(conversations.lockById(id)).thenReturn(java.util.Optional.of(c));
        when(messages.maxSeq(id)).thenReturn(1);
        when(messages.save(any())).thenAnswer(i -> {
            ChatMessageRecord m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        assertNotNull(service.appendAssistantMessage(id, run, mapper.createObjectNode()));
    }

    @Test
    void retryAfterAStopMakesTheNewRunWinAndDropsTheOldStoppedWrite() {
        UUID id = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        ChatConversation c = conv(id);
        c.setActiveRunId(oldRun);
        when(conversations.lockById(id)).thenReturn(java.util.Optional.of(c));
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of(msg(id, 1, "USER", "q")));
        when(messages.maxSeq(id)).thenReturn(1);
        when(messages.save(any())).thenAnswer(i -> {
            ChatMessageRecord m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        assertEquals("q", service.prepareRetry(id, newRun));
        assertEquals(newRun, c.getActiveRunId());
        assertNull(service.appendAssistantMessage(id, oldRun, mapper.createObjectNode()));
        assertNotNull(service.appendAssistantMessage(id, newRun, mapper.createObjectNode()));
        verify(messages, times(1)).save(any());
    }

    @Test
    void retryTakesTheConversationLock() {
        UUID id = UUID.randomUUID();
        when(messages.findByConversationIdOrderBySeqAsc(id)).thenReturn(List.of());
        service.prepareRetry(id, UUID.randomUUID());
        verify(conversations).lockById(id);
    }
}
