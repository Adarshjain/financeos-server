package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Component
public class CalcTool implements ChatTool {

    private final ObjectMapper objectMapper;

    public CalcTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "calc";
    }

    @Override
    public String description() {
        return "Perform accurate mathematical arithmetic on numbers derived from SQL queries or tools. Always use calc instead of doing mental math.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("expression").put("type", "string").put("description", "Math expression e.g. '(a / b) * 100'");
        ObjectNode vals = props.putObject("values");
        vals.put("type", "object");
        vals.put("description", "Key-value map of variable names to numbers used in expression");
        schema.putArray("required").add("expression");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            if (args == null || !args.has("expression") || args.get("expression").asText().isBlank()) {
                return ChatToolResult.failure(name(), "expression is required");
            }

            String expressionStr = args.get("expression").asText().trim();
            if (expressionStr.length() > 200) {
                return ChatToolResult.failure(name(), "Expression length exceeds maximum allowed cap of 200 characters");
            }

            Map<String, Double> variableValues = new HashMap<>();
            if (args.has("values") && args.get("values").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = args.get("values").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue().isNumber()) {
                        variableValues.put(entry.getKey(), entry.getValue().asDouble());
                    }
                }
            }

            ExpressionBuilder builder = new ExpressionBuilder(expressionStr);
            if (!variableValues.isEmpty()) {
                builder.variables(variableValues.keySet());
            }

            Expression expression = builder.build();
            if (!variableValues.isEmpty()) {
                for (Map.Entry<String, Double> entry : variableValues.entrySet()) {
                    expression.setVariable(entry.getKey(), entry.getValue());
                }
            }

            double result = expression.evaluate();

            ObjectNode response = objectMapper.createObjectNode();
            response.put("result", result);
            return ChatToolResult.success(name(), response);
        } catch (IllegalArgumentException e) {
            return ChatToolResult.failure(name(), "Invalid calculation expression or unknown variable: " + e.getMessage());
        } catch (Exception e) {
            return ChatToolResult.failure(name(), "Calculation failed: " + e.getMessage());
        }
    }
}
