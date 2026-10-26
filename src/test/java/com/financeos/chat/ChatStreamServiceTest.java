package com.financeos.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.api.ChatStreamRequest;
import com.financeos.chat.api.ChatStreamService;
import com.financeos.chat.api.ChatStreamService.Outcome;
import com.financeos.chat.api.ChatStreamService.Prepared;
import com.financeos.chat.history.ChatConversation;
import com.financeos.chat.history.ChatHistoryService;
import com.financeos.chat.history.ChatTitleService;
import com.financeos.chat.orchestrator.ChatAnswer;
import com.financeos.chat.orchestrator.ChatEventSink;
import com.financeos.chat.orchestrator.ChatMessage;
import com.financeos.chat.orchestrator.ChatOrchestrator;
import com.financeos.chat.orchestrator.ChatTraceEntry;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatStreamServiceTest {

    private final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatTitleService titles = mock(ChatTitleService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatStreamService service = new ChatStreamService(orchestrator, history, titles, mapper);
    private final UUID userId = UUID.randomUUID();
    private final UUID convId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    private final List<String> names = new ArrayList<>();
    private final List<JsonNode> data = new ArrayList<>();

    private ChatStreamService.EventOut out() {
        return (name, json) -> {
            names.add(name);
            data.add(mapper.readTree(json));
        };
    }

    private ChatTraceEntry trace() {
        return new ChatTraceEntry(1, "run_sql", "Executed SQL query", "select 1", 1, 5L, true, null, null, "Queried your data");
    }

    private void answers(ChatAnswer answer) {
        when(orchestrator.run(any(), any())).thenAnswer(i -> {
            ChatEventSink sink = i.getArgument(1);
            sink.onStatus("Thinking…");
            sink.onTrace(trace());
            return answer;
        });
    }

    private void historyReady() {
        ChatConversation c = new ChatConversation();
        c.setId(convId);
        when(history.create(userId)).thenReturn(c);
        when(history.appendAssistantMessage(eq(convId), any(), any())).thenReturn(messageId);
        when(history.buildPayload(any(), any(), anyLong(), anyString(), any())).thenReturn(mapper.createObjectNode());
    }

    @Test
    void statelessRequestsBehaveAsBeforeAndNeverTouchStorage() {
        ChatStreamRequest req = new ChatStreamRequest(List.of(new ChatMessage("user", "hi")));
        answers(ChatAnswer.answer("Hello", List.of(trace())));

        Prepared p = service.prepare(userId, req);
        Outcome outcome = service.execute(userId, p, out(), () -> false);

        assertEquals(Outcome.DONE, outcome);
        assertEquals(List.of("status", "trace", "final"), names);
        assertEquals(List.of(new ChatMessage("user", "hi")), p.transcript());
        JsonNode fin = data.get(2);
        assertEquals("Hello", fin.get("answer").asText());
        assertFalse(fin.has("messageId"));
        assertFalse(fin.has("conversationId"));
        assertFalse(fin.has("durationMs"));
        assertEquals("Queried your data", data.get(1).get("label").asText());
        verifyNoInteractions(history, titles);
    }

    @Test
    void statelessErrorKeepsItsShape() {
        when(orchestrator.run(any(), any())).thenThrow(new RuntimeException("boom"));
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "hi"))));

        assertEquals(Outcome.ERROR, service.execute(userId, p, out(), () -> false));

        assertEquals(List.of("error"), names);
        assertEquals("EXECUTION_ERROR", data.get(0).get("code").asText());
        assertFalse(data.get(0).has("messageId"));
        verifyNoInteractions(history);
    }

    @Test
    void newConversationSendsConversationFirstThenFinalThenTitle() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of(new ChatMessage("user", "q")));
        when(history.needsTitle(convId)).thenReturn(true);
        when(history.firstQuestion(convId)).thenReturn("q");
        when(titles.generate(userId, "q", "A")).thenReturn("Spending summary");
        when(history.setTitleIfNull(convId, "Spending summary")).thenReturn(true);
        answers(ChatAnswer.answer("A", List.of(trace())));

        ChatStreamRequest req = new ChatStreamRequest(List.of(new ChatMessage("user", "q")), null, true, null);
        Prepared p = service.prepare(userId, req);
        assertEquals(Outcome.DONE, service.execute(userId, p, out(), () -> false));

        assertEquals(List.of("conversation", "status", "trace", "final", "title"), names);
        assertEquals(convId.toString(), data.get(0).get("id").asText());
        JsonNode fin = data.get(3);
        assertEquals(messageId.toString(), fin.get("messageId").asText());
        assertEquals(convId.toString(), fin.get("conversationId").asText());
        assertTrue(fin.get("durationMs").isNumber());
        assertEquals("Spending summary", data.get(4).get("title").asText());
        assertEquals(convId.toString(), data.get(4).get("conversationId").asText());
        verify(history).appendUserMessage(eq(convId), eq("q"), any());
        verify(history).buildPayload(any(), any(), anyLong(), eq("COMPLETE"), eq(null));
    }

    @Test
    void persistedRunUsesTheStoredTranscriptNotTheRequestOnes() {
        historyReady();
        List<ChatMessage> stored = List.of(new ChatMessage("user", "old q"), new ChatMessage("assistant", "old a"),
                new ChatMessage("user", "new q"));
        when(history.transcript(convId)).thenReturn(stored);
        when(history.needsTitle(convId)).thenReturn(false);
        answers(ChatAnswer.answer("A", List.of()));

        ChatStreamRequest req = new ChatStreamRequest(
                List.of(new ChatMessage("user", "client says"), new ChatMessage("user", "new q")), convId, false, null);
        Prepared p = service.prepare(userId, req);
        service.execute(userId, p, out(), () -> false);

        verify(history).appendUserMessage(eq(convId), eq("new q"), any());
        ArgumentCaptor<List<ChatMessage>> seen = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).run(seen.capture(), any());
        assertEquals(stored, seen.getValue());
        assertEquals(List.of("status", "trace", "final"), names);
        verifyNoInteractions(titles);
    }

    @Test
    void existingTitleIsNotRegenerated() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of());
        when(history.needsTitle(convId)).thenReturn(false);
        answers(ChatAnswer.answer("A", List.of()));
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null));
        service.execute(userId, p, out(), () -> false);
        assertFalse(names.contains("title"));
        verify(titles, never()).generate(any(), any(), any());
    }

    @Test
    void clarifyAnswersArePersistedButGetNoTitle() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of());
        answers(ChatAnswer.clarify("Which card?", List.of("HDFC", "ICICI"), List.of()));
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null));

        service.execute(userId, p, out(), () -> false);

        verify(history).appendAssistantMessage(eq(convId), any(), any());
        verify(history).buildPayload(any(), any(), anyLong(), eq("COMPLETE"), eq(null));
        assertEquals("Which card?", data.get(2).get("clarify").asText());
        assertEquals(2, data.get(2).get("clarifyOptions").size());
        assertFalse(names.contains("title"));
    }

    @Test
    void retryReRunsTheLastQuestionWithoutAddingAUserMessage() {
        historyReady();
        when(history.prepareRetry(eq(convId), any())).thenReturn("q");
        when(history.transcript(convId)).thenReturn(List.of(new ChatMessage("user", "q")));
        when(history.needsTitle(convId)).thenReturn(false);
        answers(ChatAnswer.answer("A2", List.of()));

        ChatStreamRequest req = new ChatStreamRequest(List.of(), convId, null, true);
        service.validate(userId, req);
        Prepared p = service.prepare(userId, req);
        service.execute(userId, p, out(), () -> false);

        verify(history, never()).appendUserMessage(any(), any(), any());
        verify(history, never()).create(any());
        assertFalse(names.contains("conversation"));
        assertTrue(names.contains("final"));
    }

    @Test
    void failureStoresAFailedMessageWithStepsAndSendsErrorWithMessageId() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of());
        when(orchestrator.run(any(), any())).thenAnswer(i -> {
            ((ChatEventSink) i.getArgument(1)).onTrace(trace());
            throw new IllegalStateException("db down");
        });
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null));

        assertEquals(Outcome.ERROR, service.execute(userId, p, out(), () -> false));

        ArgumentCaptor<List<ChatTraceEntry>> steps = ArgumentCaptor.forClass(List.class);
        verify(history).buildPayload(eq(null), steps.capture(), anyLong(), eq("FAILED"), any());
        assertEquals(1, steps.getValue().size());
        assertEquals(List.of("trace", "error"), names);
        ObjectNode err = (ObjectNode) data.get(1);
        assertEquals("EXECUTION_ERROR", err.get("code").asText());
        assertEquals(messageId.toString(), err.get("messageId").asText());
        assertNotNull(err.get("message"));
    }

    @Test
    void clientDisconnectStoresStoppedAndSendsNothingMore() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of());
        answers(ChatAnswer.answer("Cancelled.", List.of(trace())));
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null));

        assertEquals(Outcome.STOPPED, service.execute(userId, p, out(), () -> true));

        verify(history).buildPayload(eq(null), any(), anyLong(), eq("STOPPED"), eq(null));
        assertFalse(names.contains("final"));
        assertFalse(names.contains("title"));
    }

    @Test
    void validateRejectsPersistedRequestsWithoutAQuestion() {
        ChatStreamRequest noQuestion = new ChatStreamRequest(List.of(new ChatMessage("assistant", "hi")), null, true, null);
        assertThrows(ValidationException.class, () -> service.validate(userId, noQuestion));
        ChatStreamRequest retryNew = new ChatStreamRequest(List.of(), null, true, true);
        assertThrows(ValidationException.class, () -> service.validate(userId, retryNew));
    }

    @Test
    void validatePropagatesNotFoundForSomeoneElsesConversation() {
        when(history.requireOwned(userId, convId)).thenThrow(new ResourceNotFoundException("Chat conversation", convId));
        ChatStreamRequest req = new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null);
        assertThrows(ResourceNotFoundException.class, () -> service.validate(userId, req));
        verify(history, never()).appendUserMessage(any(), any(), any());
    }

    @Test
    void retryOnAConversationWithoutAQuestionIsRejected() {
        when(history.prepareRetry(eq(convId), any())).thenReturn(null);
        ChatStreamRequest req = new ChatStreamRequest(List.of(), convId, null, true);
        assertThrows(ValidationException.class, () -> service.prepare(userId, req));
    }

    @Test
    void finalSendFailureAfterStoringDoesNotStoreASecondMessage() {
        historyReady();
        when(history.transcript(convId)).thenReturn(List.of());
        answers(ChatAnswer.answer("A", List.of()));
        Prepared p = service.prepare(userId, new ChatStreamRequest(List.of(new ChatMessage("user", "q")), convId, null, null));

        ChatStreamService.Outcome outcome = service.execute(userId, p, (name, json) -> {
            if ("final".equals(name)) {
                throw new java.io.IOException("client gone");
            }
        }, () -> false);

        assertEquals(ChatStreamService.Outcome.STOPPED, outcome);
        verify(history, times(1)).appendAssistantMessage(eq(convId), any(), any());
    }
}
