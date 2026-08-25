package com.financeos.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.chat.db.ChatProperties;
import com.financeos.chat.db.ChatSqlExecutor;
import com.financeos.chat.orchestrator.*;
import com.financeos.chat.tool.ChatToolRegistry;
import com.financeos.chat.tool.impl.CalcTool;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatOrchestratorTest {

    private LlmClient mockLlmClient;
    private ChatSqlExecutor mockSqlExecutor;
    private ChatGroundingService mockGroundingService;
    private ChatOrchestrator orchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockLlmClient = mock(LlmClient.class);
        mockSqlExecutor = mock(ChatSqlExecutor.class);
        mockGroundingService = mock(ChatGroundingService.class);
        objectMapper = new ObjectMapper();

        when(mockGroundingService.buildGroundingBlock()).thenReturn("Grounding Context Mock");

        CalcTool calcTool = new CalcTool(objectMapper);
        ChatToolRegistry registry = new ChatToolRegistry(List.of(calcTool));

        ChatProperties properties = new ChatProperties();
        properties.getLoop().setMaxIterations(6);

        orchestrator = new ChatOrchestrator(
                mockLlmClient, mockSqlExecutor, registry, mockGroundingService, properties, objectMapper, new DefaultResourceLoader()
        );
    }

    @Test
    @DisplayName("Happy path: SQL query -> calc tool -> final answer")
    void happyPathOrchestration() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"run_sql\",\"reasoning\":\"Query transactions\",\"sql\":\"SELECT * FROM v_chat_transactions\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"calc\",\"reasoning\":\"Calculate total\",\"args\":{\"expression\":\"100 + 200\"}}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp3 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Your total spend is ₹300.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2)
                .thenReturn(resp3);

        when(mockSqlExecutor.execute("SELECT * FROM v_chat_transactions"))
                .thenReturn("{\"columns\":[\"amount\"],\"rows\":[[100],[200]],\"rowCount\":2}");

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "How much did I spend?"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertNotNull(answer.answer());
        assertEquals("Your total spend is ₹300.", answer.answer());
        assertEquals(2, answer.traces().size());
        assertEquals("run_sql", answer.traces().get(0).action());
        assertEquals("call_tool", answer.traces().get(1).action());
    }

    @Test
    @DisplayName("Clarify short-circuit stops orchestration immediately")
    void clarifyShortCircuit() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"clarify\",\"question\":\"Which month are you interested in?\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(resp1);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "Show my spend"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertNull(answer.answer());
        assertEquals("Which month are you interested in?", answer.clarify());
        assertTrue(answer.traces().isEmpty());
    }

    @Test
    @DisplayName("Iteration cap forces final answer")
    void iterationCapForcesFinalAnswer() {
        LlmResponse loopResp = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT * FROM v_chat_transactions\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse finalCapResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Based on the partial data, your total spend is approximately ₹500.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(finalCapResp);

        when(mockSqlExecutor.execute(anyString()))
                .thenReturn("{\"columns\":[\"amount\"],\"rows\":[[500]],\"rowCount\":1}");

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "Calculate my spend"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertNotNull(answer.answer());
        assertTrue(answer.answer().contains("approximately ₹500"));
        assertEquals(5, answer.traces().size());
    }

    @Test
    @DisplayName("Blank answer mid-loop retries with corrective note and returns subsequent answer")
    void blankAnswerMidLoopRetries() {
        LlmResponse badResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"reasoning\":\"Calculated 100\"}",
                "gemini", "gemini-3.5-flash"
        );
        LlmResponse goodResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Your total is ₹100.\"}",
                "gemini", "gemini-3.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(badResp)
                .thenReturn(goodResp);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "What is my total?"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Your total is ₹100.", answer.answer());

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(2)).complete(reqCaptor.capture());
        assertTrue(reqCaptor.getAllValues().get(1).prompt().contains("final_answer rejected"));
    }

    @Test
    @DisplayName("Blank answer on last iteration falls back to reasoning")
    void blankAnswerLastIterationFallbackToReasoning() {
        LlmResponse loopResp = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT * FROM v_chat_transactions\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse badFinalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"reasoning\":\"Reasoning fallback answer\"}",
                "gemini", "gemini-3.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(badFinalResp);

        when(mockSqlExecutor.execute(anyString()))
                .thenReturn("{\"columns\":[\"amount\"],\"rows\":[[100]],\"rowCount\":1}");

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "Calculate spend"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Reasoning fallback answer", answer.answer());
    }

    @Test
    @DisplayName("Final iteration schema has required action and answer")
    void finalIterationSchemaHasRequiredActionAndAnswer() {
        LlmResponse loopResp = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT * FROM v_chat_transactions\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Done\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(loopResp)
                .thenReturn(finalResp);

        when(mockSqlExecutor.execute(anyString()))
                .thenReturn("{\"columns\":[\"amount\"],\"rows\":[[100]],\"rowCount\":1}");

        orchestrator.run(List.of(new ChatMessage("user", "test")), ChatEventSink.NOOP);

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(6)).complete(reqCaptor.capture());

        LlmRequest firstReq = reqCaptor.getAllValues().get(0);
        JsonNode firstReqSchema = firstReq.responseSchema();
        assertEquals(1, firstReqSchema.path("required").size());
        assertEquals("action", firstReqSchema.path("required").get(0).asText());

        LlmRequest lastReq = reqCaptor.getAllValues().get(5);
        JsonNode lastReqSchema = lastReq.responseSchema();
        assertEquals(2, lastReqSchema.path("required").size());
        assertEquals("action", lastReqSchema.path("required").get(0).asText());
        assertEquals("answer", lastReqSchema.path("required").get(1).asText());
    }
}
