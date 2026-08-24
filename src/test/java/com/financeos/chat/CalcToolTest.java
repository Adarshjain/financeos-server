package com.financeos.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.chat.tool.impl.CalcTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalcToolTest {

    private CalcTool calcTool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        calcTool = new CalcTool(objectMapper);
    }

    @Test
    @DisplayName("Evaluate simple arithmetic expressions")
    void evaluateSimpleExpression() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("expression", "(100 + 50) * 2");

        ChatToolResult result = calcTool.execute(args);
        assertTrue(result.success());
        assertEquals(300.0, result.result().get("result").asDouble());
    }

    @Test
    @DisplayName("Evaluate expression with variable substitution")
    void evaluateWithVariables() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("expression", "(spend / total) * 100");
        ObjectNode values = args.putObject("values");
        values.put("spend", 250.0);
        values.put("total", 1000.0);

        ChatToolResult result = calcTool.execute(args);
        assertTrue(result.success());
        assertEquals(25.0, result.result().get("result").asDouble());
    }

    @Test
    @DisplayName("Reject expressions exceeding 200 characters")
    void rejectLongExpression() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("expression", "1+".repeat(110) + "1");

        ChatToolResult result = calcTool.execute(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("exceeds maximum allowed cap"));
    }

    @Test
    @DisplayName("Reject expressions with unknown/unsupplied variables")
    void rejectUnknownVariable() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("expression", "x + y");

        ChatToolResult result = calcTool.execute(args);
        assertFalse(result.success());
    }
}
