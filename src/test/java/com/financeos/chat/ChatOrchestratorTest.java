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
    @DisplayName("Tool call with JSON string args successfully parses and executes")
    void toolCallWithJsonStringArgs() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"calc\",\"args\":\"{\\\"expression\\\":\\\"100 + 50\\\"}\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Calculated 150.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "100 + 50"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Calculated 150.", answer.answer());
        assertEquals(1, answer.traces().size());
        assertTrue(answer.traces().get(0).success());
        assertNull(answer.traces().get(0).error());
    }

    @Test
    @DisplayName("Tool call with JSON object args still works backwards-compatibly")
    void toolCallWithJsonObjectArgs() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"calc\",\"args\":{\"expression\":\"100 + 50\"}}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Calculated 150.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "100 + 50"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Calculated 150.", answer.answer());
        assertEquals(1, answer.traces().size());
        assertTrue(answer.traces().get(0).success());
    }

    @Test
    @DisplayName("Tool call with invalid JSON args feeds back parse error and continues loop")
    void toolCallWithInvalidJsonArgsFeedsError() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"calc\",\"args\":\"not json\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Recovered after bad json.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "test"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Recovered after bad json.", answer.answer());
        assertEquals(1, answer.traces().size());
        assertFalse(answer.traces().get(0).success());
        assertNotNull(answer.traces().get(0).error());

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(2)).complete(reqCaptor.capture());
        assertTrue(reqCaptor.getAllValues().get(1).prompt().contains("args was not valid JSON"));
    }

    @Test
    @DisplayName("Security: userId in JSON string args is stripped before tool receives it")
    void userIdInStringArgsIsStripped() {
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"calc\",\"args\":\"{\\\"userId\\\":\\\"evil\\\",\\\"user_id\\\":\\\"evil2\\\",\\\"expression\\\":\\\"20 * 5\\\"}\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Result is 100.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "multiply"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Result is 100.", answer.answer());
        ChatTraceEntry trace = answer.traces().get(0);
        assertTrue(trace.success());
        assertFalse(trace.detail().contains("evil"));
    }

    @Test
    @DisplayName("Repeated failure circuit breaker: 3 identical failures force final answer before iteration cap")
    void repeatedFailureCircuitBreakerForcesFinalAnswer() {
        // Unknown tool or repeated error 3 times
        LlmResponse badResp = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"non_existent_tool\",\"args\":\"{}\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Synthesized answer after repeated failures.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(badResp)
                .thenReturn(badResp)
                .thenReturn(badResp)
                .thenReturn(finalResp);

        List<ChatMessage> transcript = List.of(new ChatMessage("user", "Run something"));
        ChatAnswer answer = orchestrator.run(transcript, ChatEventSink.NOOP);

        assertEquals("Synthesized answer after repeated failures.", answer.answer());
        // Should complete in 4 iterations instead of 6 max iterations
        verify(mockLlmClient, times(4)).complete(any(LlmRequest.class));
        assertEquals(3, answer.traces().size());
        assertFalse(answer.traces().get(0).success());
        assertFalse(answer.traces().get(1).success());
        assertFalse(answer.traces().get(2).success());

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(4)).complete(reqCaptor.capture());

        // 2nd failure appends REPEATED FAILURE directive
        assertTrue(reqCaptor.getAllValues().get(2).prompt().contains("REPEATED FAILURE"));

        // 4th request has final-only schema
        JsonNode fourthSchema = reqCaptor.getAllValues().get(3).responseSchema();
        assertEquals(1, fourthSchema.path("properties").path("action").path("enum").size());
        assertEquals("final_answer", fourthSchema.path("properties").path("action").path("enum").get(0).asText());
    }

    @Test
    @DisplayName("Standard schema declares args as string with JSON description")
    void standardSchemaDeclaresArgsAsString() {
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Done\"}",
                "gemini", "gemini-2.5-flash"
        );
        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(finalResp);

        orchestrator.run(List.of(new ChatMessage("user", "test")), ChatEventSink.NOOP);

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient).complete(reqCaptor.capture());

        JsonNode schema = reqCaptor.getValue().responseSchema();
        JsonNode argsProp = schema.path("properties").path("args");
        assertEquals("string", argsProp.path("type").asText());
        assertTrue(argsProp.path("description").asText().contains("JSON-encoded object"));
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

    @Test
    @DisplayName("Wall clock budget forces final answer on subsequent iteration")
    void wallClockBudgetForcesFinalAnswer() {
        ChatProperties fastBudgetProps = new ChatProperties();
        fastBudgetProps.getLoop().setMaxIterations(6);
        fastBudgetProps.getLoop().setMaxWallClockSeconds(0); // 0s budget triggers immediately on iteration > 1

        CalcTool calcTool = new CalcTool(objectMapper);
        ChatToolRegistry registry = new ChatToolRegistry(List.of(calcTool));
        ChatOrchestrator fastOrchestrator = new ChatOrchestrator(
                mockLlmClient, mockSqlExecutor, registry, mockGroundingService, fastBudgetProps, objectMapper, new DefaultResourceLoader()
        );

        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT * FROM v_chat_transactions\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Synthesized answer within time budget.\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        when(mockSqlExecutor.execute(anyString()))
                .thenReturn("{\"columns\":[\"amount\"],\"rows\":[[100]],\"rowCount\":1}");

        ChatEventSink mockSink = mock(ChatEventSink.class);
        ChatAnswer answer = fastOrchestrator.run(List.of(new ChatMessage("user", "test")), mockSink);

        assertEquals("Synthesized answer within time budget.", answer.answer());
        verify(mockLlmClient, times(2)).complete(any(LlmRequest.class));
        verify(mockSink).onStatus("Wrapping up…");

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(2)).complete(reqCaptor.capture());

        LlmRequest secondReq = reqCaptor.getAllValues().get(1);
        JsonNode secondSchema = secondReq.responseSchema();
        assertEquals(1, secondSchema.path("properties").path("action").path("enum").size());
        assertEquals("final_answer", secondSchema.path("properties").path("action").path("enum").get(0).asText());
        assertTrue(secondReq.prompt().contains("ATTENTION: This is the final step"));
    }

    @Test
    @DisplayName("Result preview is set on successful SQL and tool steps, truncated at 400 chars, null on error")
    void resultPreviewInTracesAndTruncation() {
        String longString = "A".repeat(500);
        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT 1\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"calc\",\"args\":{\"expression\":\"100 + 200\"}}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp3 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Done\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2)
                .thenReturn(resp3);

        when(mockSqlExecutor.execute("SELECT 1"))
                .thenReturn("{\"data\":\"" + longString + "\"}");

        ChatAnswer answer = orchestrator.run(List.of(new ChatMessage("user", "test")), ChatEventSink.NOOP);

        assertEquals(2, answer.traces().size());

        // SQL step preview truncated to 400 + "…"
        ChatTraceEntry sqlTrace = answer.traces().get(0);
        assertTrue(sqlTrace.success());
        assertNotNull(sqlTrace.resultPreview());
        assertEquals(401, sqlTrace.resultPreview().length());
        assertTrue(sqlTrace.resultPreview().endsWith("…"));

        // Tool step preview not exceeding 400
        ChatTraceEntry toolTrace = answer.traces().get(1);
        assertTrue(toolTrace.success());
        assertNotNull(toolTrace.resultPreview());
        assertTrue(toolTrace.resultPreview().contains("300"));
        assertFalse(toolTrace.resultPreview().endsWith("…"));
    }

    @Test
    @DisplayName("Tool returning JSON array sets rowCount to array length in trace")
    void toolArrayResultSetsRowCount() {
        com.financeos.chat.tool.ChatTool arrayTool = new com.financeos.chat.tool.ChatTool() {
            @Override
            public String name() {
                return "array_tool";
            }

            @Override
            public String description() {
                return "returns array";
            }

            @Override
            public JsonNode argsSchema() {
                return objectMapper.createObjectNode();
            }

            @Override
            public com.financeos.chat.tool.ChatToolResult execute(JsonNode args) {
                com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
                array.addObject().put("item", 1);
                array.addObject().put("item", 2);
                array.addObject().put("item", 3);
                return com.financeos.chat.tool.ChatToolResult.success(name(), array);
            }
        };

        ChatToolRegistry registry = new ChatToolRegistry(List.of(arrayTool));
        ChatProperties props = new ChatProperties();
        props.getLoop().setMaxIterations(6);
        ChatOrchestrator arrayOrchestrator = new ChatOrchestrator(
                mockLlmClient, mockSqlExecutor, registry, mockGroundingService, props, objectMapper, new DefaultResourceLoader()
        );

        LlmResponse resp1 = new LlmResponse(
                "{\"action\":\"call_tool\",\"tool\":\"array_tool\",\"args\":\"{}\"}",
                "gemini", "gemini-2.5-flash"
        );
        LlmResponse resp2 = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Done\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        ChatAnswer answer = arrayOrchestrator.run(List.of(new ChatMessage("user", "test")), ChatEventSink.NOOP);

        assertEquals(1, answer.traces().size());
        ChatTraceEntry trace = answer.traces().get(0);
        assertTrue(trace.success());
        assertEquals(3, trace.rowCount());
        assertNotNull(trace.resultPreview());
    }

    @Test
    @DisplayName("Final answer with valid blocks string parses and returns blocks in ChatAnswer")
    void finalAnswerWithValidBlocks() {
        String blocksJson = "{\\\"stats\\\":[{\\\"label\\\":\\\"Total\\\",\\\"value\\\":\\\"₹100\\\"}],\\\"followUps\\\":[\\\"Next question?\\\"]}";
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Here is your spend.\",\"blocks\":\"" + blocksJson + "\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(finalResp);

        ChatAnswer answer = orchestrator.run(List.of(new ChatMessage("user", "Spend")), ChatEventSink.NOOP);

        assertEquals("Here is your spend.", answer.answer());
        assertNotNull(answer.blocks());
        assertEquals("Total", answer.blocks().path("stats").get(0).path("label").asText());
        assertEquals("₹100", answer.blocks().path("stats").get(0).path("value").asText());
        assertEquals("Next question?", answer.blocks().path("followUps").get(0).asText());
    }

    @Test
    @DisplayName("Final answer with invalid blocks string still returns answer and null blocks")
    void finalAnswerWithInvalidBlocksKeepsAnswerAndNullBlocks() {
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Here is your spend.\",\"blocks\":\"invalid_json_garbage\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(finalResp);

        ChatAnswer answer = orchestrator.run(List.of(new ChatMessage("user", "Spend")), ChatEventSink.NOOP);

        assertEquals("Here is your spend.", answer.answer());
        assertNull(answer.blocks());
    }

    @Test
    @DisplayName("Schema test: standard and final-only schemas both have optional blocks of type string")
    void schemaDeclaresOptionalBlocksString() {
        LlmResponse loopResp = new LlmResponse(
                "{\"action\":\"run_sql\",\"sql\":\"SELECT 1\"}",
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

        when(mockSqlExecutor.execute(anyString())).thenReturn("{\"rowCount\":0}");

        orchestrator.run(List.of(new ChatMessage("user", "test")), ChatEventSink.NOOP);

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient, times(6)).complete(reqCaptor.capture());

        // Standard schema
        JsonNode standardSchema = reqCaptor.getAllValues().get(0).responseSchema();
        JsonNode stdBlocks = standardSchema.path("properties").path("blocks");
        assertEquals("string", stdBlocks.path("type").asText());
        assertTrue(stdBlocks.path("description").asText().contains("OPTIONAL JSON-encoded"));
        assertEquals(1, standardSchema.path("required").size()); // ["action"]

        // Final-only schema
        JsonNode finalSchema = reqCaptor.getAllValues().get(5).responseSchema();
        JsonNode finalBlocks = finalSchema.path("properties").path("blocks");
        assertEquals("string", finalBlocks.path("type").asText());
        assertTrue(finalBlocks.path("description").asText().contains("OPTIONAL JSON-encoded"));
        assertEquals(2, finalSchema.path("required").size()); // ["action", "answer"]
    }

    @Test
    @DisplayName("Final answer with reportDraft block parses and attaches reportDraft to ChatAnswer")
    void finalAnswerWithReportDraft() {
        String blocksJson = "{\\\"reportDraft\\\":{\\\"mode\\\":\\\"create\\\",\\\"name\\\":\\\"Draft 1\\\",\\\"type\\\":\\\"KPI\\\",\\\"datasource\\\":\\\"transactions\\\",\\\"definition\\\":{\\\"measure\\\":\\\"amount\\\"}}}";
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Your report draft is ready below.\",\"blocks\":\"" + blocksJson + "\"}",
                "gemini", "gemini-2.5-flash"
        );

        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(finalResp);

        ChatAnswer answer = orchestrator.run(List.of(new ChatMessage("user", "Create a report")), ChatEventSink.NOOP);

        assertEquals("Your report draft is ready below.", answer.answer());
        assertNotNull(answer.blocks());
        assertTrue(answer.blocks().has("reportDraft"));
        assertEquals("create", answer.blocks().path("reportDraft").path("mode").asText());
        assertEquals("Draft 1", answer.blocks().path("reportDraft").path("name").asText());
        assertEquals("KPI", answer.blocks().path("reportDraft").path("type").asText());
    }

    @Test
    @DisplayName("computeForceFinalAtMs and computeHardDeadlineMs calculation and floor constraints")
    void deadlineAndForceFinalCalculations() {
        // Standard config: wallClock=90s, emitter=290s
        long forceFinal1 = ChatOrchestrator.computeForceFinalAtMs(90_000L, 290_000L);
        assertEquals(90_000L, forceFinal1); // min(90_000, 200_000) = 90_000

        long hardDeadline1 = ChatOrchestrator.computeHardDeadlineMs(290_000L);
        assertEquals(270_000L, hardDeadline1); // 290_000 - 20_000 = 270_000

        // Large wall clock, small emitter: wallClock=300s, emitter=120s
        long forceFinal2 = ChatOrchestrator.computeForceFinalAtMs(300_000L, 120_000L);
        assertEquals(30_000L, forceFinal2); // min(300_000, 120_000 - 90_000) = 30_000

        long hardDeadline2 = ChatOrchestrator.computeHardDeadlineMs(120_000L);
        assertEquals(100_000L, hardDeadline2); // 120_000 - 20_000 = 100_000

        // Floors applied for tiny emitter timeouts
        long forceFinalFloored = ChatOrchestrator.computeForceFinalAtMs(90_000L, 50_000L);
        assertEquals(30_000L, forceFinalFloored); // 50_000 - 90_000 is negative -> floored at 30_000

        long hardDeadlineFloored = ChatOrchestrator.computeHardDeadlineMs(15_000L);
        assertEquals(10_000L, hardDeadlineFloored); // 15_000 - 20_000 is negative -> floored at 10_000
    }

    @Test
    @DisplayName("Prompt includes Rule 21 prohibiting silent constraint drops")
    void promptIncludesRule21NoSilentDrops() {
        LlmResponse finalResp = new LlmResponse(
                "{\"action\":\"final_answer\",\"answer\":\"Here is your answer.\"}",
                "gemini", "gemini-2.5-flash"
        );
        when(mockLlmClient.complete(any(LlmRequest.class))).thenReturn(finalResp);

        orchestrator.run(List.of(new ChatMessage("user", "Show me my expenses")), ChatEventSink.NOOP);

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockLlmClient).complete(reqCaptor.capture());

        String prompt = reqCaptor.getValue().prompt();
        assertTrue(prompt.contains("21. NEVER emit a reportDraft that silently drops any filter, dimension, or constraint"));
        assertTrue(prompt.contains("the final answer MUST clearly state what could not be done and why, in plain terms"));
    }
}
