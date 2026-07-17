package com.financeos.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.LlmProvider;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private final String id;
    private final LlmProperties.ProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiProvider(String id, LlmProperties.ProviderProperties properties, ObjectMapper objectMapper) {
        this.id = id;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String apiKey = properties.getApiKey();
        if ((apiKey == null || apiKey.trim().isEmpty()) && !properties.isAllowNoKey()) {
            throw new LlmException(LlmException.Kind.FATAL, id, null, null, "API key is not configured for Gemini provider: " + id);
        }

        String requestBody;
        try {
            requestBody = buildRequestBody(request, objectMapper);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.FATAL, id, null, null, "Failed to build request body: " + e.getMessage(), e);
        }

        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                properties.getModel() != null ? properties.getModel() : "gemini-2.5-flash-lite",
                apiKey != null ? apiKey.trim() : ""
        );

        long timeoutMs = properties.getTimeoutMs();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        String modelName = properties.getModel() != null ? properties.getModel() : "gemini-2.5-flash-lite";
        log.info("Making API call to provider [{}], model [{}], task [{}]", id, modelName, request.task());
        HttpResponse<String> response = LlmHttpSupport.executeAndHandleExceptions(
                () -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()), id);
        return parseResponseBody(response.body(), response.statusCode(), LlmHttpSupport.parseRetryAfter(response), id, properties.getModel(), objectMapper);
    }

    public static String buildRequestBody(LlmRequest request, ObjectMapper objectMapper) throws Exception {
        ObjectNode requestJson = objectMapper.createObjectNode();
        ObjectNode contentPart = objectMapper.createObjectNode().put("text", request.prompt() != null ? request.prompt() : "");
        requestJson.putArray("contents")
                .addObject()
                .putArray("parts")
                .add(contentPart);

        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", request.temperature());

        if (request.responseSchema() != null && !request.responseSchema().isMissingNode() && !request.responseSchema().isNull()) {
            JsonNode convertedSchema = convertSchemaToGemini(request.responseSchema(), objectMapper);
            if (convertedSchema != null) {
                generationConfig.set("responseSchema", convertedSchema);
            }
        }
        requestJson.set("generationConfig", generationConfig);

        return objectMapper.writeValueAsString(requestJson);
    }

    public static LlmResponse parseResponseBody(String body, int statusCode, Long retryAfter, String providerId, String model, ObjectMapper objectMapper) {
        LlmHttpSupport.classifyStatus(statusCode, body, retryAfter, providerId);

        try {
            JsonNode responseJson = objectMapper.readTree(body);
            JsonNode textNode = responseJson.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode() || textNode.isNull() || textNode.asText().trim().isEmpty()) {
                log.error("Missing or empty text in {} response: {}", providerId, body);
                throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Missing or empty text in " + providerId + " response: " + LlmHttpSupport.truncate(body, 200));
            }
            String jsonText = textNode.asText();
            try {
                objectMapper.readTree(jsonText);
            } catch (Exception e) {
                log.error("Failed to parse JSON text in {} response: {}", providerId, jsonText, e);
                throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Failed to parse JSON text in " + providerId + " response: " + e.getMessage() + ", raw: " + LlmHttpSupport.truncate(jsonText, 200), e);
            }
            return new LlmResponse(jsonText, providerId, model);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("Invalid {} response format: {}", providerId, body, e);
            throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Invalid " + providerId + " response format: " + e.getMessage() + ", raw: " + LlmHttpSupport.truncate(body, 200), e);
        }
    }

    public static JsonNode convertSchemaToGemini(JsonNode schema, ObjectMapper objectMapper) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        if (!schema.isObject()) {
            return schema;
        }
        ObjectNode result = objectMapper.createObjectNode();
        if (schema.has("type") && schema.get("type").isTextual()) {
            result.put("type", schema.get("type").asText().toUpperCase());
        }
        if (schema.has("description") && schema.get("description").isTextual()) {
            result.put("description", schema.get("description").asText());
        }
        if (schema.has("enum") && schema.get("enum").isArray()) {
            result.set("enum", schema.get("enum").deepCopy());
        }
        if (schema.has("required") && schema.get("required").isArray()) {
            result.set("required", schema.get("required").deepCopy());
        }
        if (schema.has("properties") && schema.get("properties").isObject()) {
            ObjectNode props = result.putObject("properties");
            schema.get("properties").fields().forEachRemaining(entry -> {
                props.set(entry.getKey(), convertSchemaToGemini(entry.getValue(), objectMapper));
            });
        }
        if (schema.has("items")) {
            result.set("items", convertSchemaToGemini(schema.get("items"), objectMapper));
        }
        return result;
    }
}
