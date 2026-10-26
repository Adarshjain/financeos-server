package com.financeos.chat.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatTitleServiceTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ChatTitleService service = new ChatTitleService(llm, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    @Test
    void usesTheLlmTitleOnItsOwnTaskAndCleansIt() {
        when(llm.complete(any())).thenReturn(new LlmResponse("{\"title\":\"\\\"Spending last month.\\\"\"}", "p", "m"));

        String title = service.generate(userId, "How much did I spend last month?", "You spent 42k.");

        assertEquals("Spending last month", title);
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).complete(req.capture());
        assertEquals("data-chat-title", req.getValue().task());
        assertEquals(userId, req.getValue().userId());
    }

    @Test
    void fallsBackToTheQuestionWhenTheLlmFails() {
        when(llm.complete(any())).thenThrow(new LlmException(LlmException.Kind.FATAL, "x", null, null, "down"));
        assertEquals("What did I spend on food", service.generate(userId, "What did I spend on food?", "a"));
    }

    @Test
    void fallsBackWhenTheLlmReturnsABlankTitle() {
        // the E2E SCHEMA_DEFAULT mode synthesises {"title": ""}
        when(llm.complete(any())).thenReturn(new LlmResponse("{\"title\":\"\"}", "scripted", "scripted-v1"));
        assertEquals("Net worth", service.generate(userId, "Net worth", "a"));
    }

    @Test
    void fallbackCutsLongQuestionsOnAWordBoundary() {
        String q = "Show me every restaurant transaction I made across all my credit cards during the last quarter";
        String t = ChatTitleService.fallback(q);
        assertTrue(t.length() <= 60);
        assertTrue(q.startsWith(t));
        assertFalse(t.endsWith(" "));
        assertEquals("Show me every restaurant transaction I made across all my", t);
    }

    @Test
    void fallbackHandlesEmptyAndShortInput() {
        assertEquals("New chat", ChatTitleService.fallback("  "));
        assertEquals("New chat", ChatTitleService.fallback(null));
        assertEquals("Net worth", ChatTitleService.fallback("  Net   worth? "));
    }
}
