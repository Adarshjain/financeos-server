package com.financeos.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    private final String id;
    private final LlmProperties.ProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatProvider(String id, LlmProperties.ProviderProperties properties, ObjectMapper objectMapper) {
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
            throw new LlmException(LlmException.Kind.FATAL, id, null, null, "API key is not configured for OpenAI provider: " + id);
        }

        String requestBody;
        try {
            requestBody = buildRequestBody(request, properties, objectMapper);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.FATAL, id, null, null, "Failed to build request body: " + e.getMessage(), e);
        }

        String baseUrl = properties.getBaseUrl() != null ? properties.getBaseUrl().trim() : "";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/chat/completions";

        long timeoutMs = properties.getTimeoutMs();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }
        if (properties.getHeaders() != null) {
            properties.getHeaders().forEach((k, v) -> {
                if (k != null && v != null) {
                    requestBuilder.header(k, v);
                }
            });
        }

        String modelName = properties.getModel() != null ? properties.getModel() : "unknown";
        log.info("Making API call to provider [{}], model [{}], task [{}]", id, modelName, request.task());
        HttpResponse<String> response = LlmHttpSupport.executeAndHandleExceptions(
                () -> httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString()), id);
        return parseResponseBody(response.body(), response.statusCode(), LlmHttpSupport.parseRetryAfter(response), id, properties.getModel(), objectMapper);
    }

    public static String buildRequestBody(LlmRequest request, LlmProperties.ProviderProperties properties, ObjectMapper objectMapper) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        if (properties.getModel() != null) {
            body.put("model", properties.getModel());
        }
        body.put("temperature", request.temperature());

        String promptText = request.prompt() != null ? request.prompt() : "";
        String structuredMode = properties.getStructuredOutput() != null ? properties.getStructuredOutput() : "json-schema";

        if ("json-object".equalsIgnoreCase(structuredMode)) {
            if (request.responseSchema() != null && !request.responseSchema().isMissingNode() && !request.responseSchema().isNull()) {
                promptText += "\n\nRespond ONLY with a JSON object matching this JSON Schema:\n" + objectMapper.writeValueAsString(request.responseSchema());
            }
            ObjectNode format = objectMapper.createObjectNode();
            format.put("type", "json_object");
            body.set("response_format", format);
        } else {
            ObjectNode format = objectMapper.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchemaObj = format.putObject("json_schema");
            String taskName = request.task() != null && !request.task().isBlank() ? request.task() : "response";
            jsonSchemaObj.put("name", taskName);
            jsonSchemaObj.put("strict", true);
            if (request.responseSchema() != null && !request.responseSchema().isMissingNode() && !request.responseSchema().isNull()) {
                JsonNode strictSchema = convertSchemaForOpenAi(request.responseSchema());
                jsonSchemaObj.set("schema", strictSchema);
            }
            body.set("response_format", format);
        }

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", promptText);
        body.putArray("messages").add(message);

        return objectMapper.writeValueAsString(body);
    }

    public static LlmResponse parseResponseBody(String body, int statusCode, Long retryAfter, String providerId, String model, ObjectMapper objectMapper) {
        LlmHttpSupport.classifyStatus(statusCode, body, retryAfter, providerId);

        try {
            JsonNode responseJson = objectMapper.readTree(body);
            JsonNode contentNode = responseJson.at("/choices/0/message/content");
            if (contentNode.isMissingNode() || contentNode.isNull() || contentNode.asText().trim().isEmpty()) {
                log.error("Missing or empty content in {} response: {}", providerId, body);
                throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Missing or empty content in " + providerId + " response: " + LlmHttpSupport.truncate(body, 200));
            }
            String rawText = contentNode.asText();
            String strippedText = stripCodeFences(rawText);
            try {
                objectMapper.readTree(strippedText);
            } catch (Exception e) {
                log.error("Failed to parse JSON text in {} response: {}", providerId, strippedText, e);
                throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Failed to parse JSON text in " + providerId + " response: " + e.getMessage() + ", raw: " + LlmHttpSupport.truncate(strippedText, 200), e);
            }
            return new LlmResponse(strippedText, providerId, model);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("Invalid {} response format: {}", providerId, body, e);
            throw new LlmException(LlmException.Kind.BAD_OUTPUT, providerId, 200, null, "Invalid " + providerId + " response format: " + e.getMessage() + ", raw: " + LlmHttpSupport.truncate(body, 200), e);
        }
    }

    public static String stripCodeFences(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            if (firstNewLine != -1) {
                int lastFence = trimmed.lastIndexOf("```");
                if (lastFence > firstNewLine) {
                    return trimmed.substring(firstNewLine + 1, lastFence).trim();
                }
            }
        }
        return trimmed;
    }

    public static JsonNode convertSchemaForOpenAi(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        JsonNode copy = schema.deepCopy();
        transformSchemaForOpenAiStrict(copy);
        return copy;
    }

    private static void transformSchemaForOpenAiStrict(JsonNode node) {
        if (node != null && node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if ((obj.has("type") && "object".equalsIgnoreCase(obj.get("type").asText())) || obj.has("properties")) {
                obj.put("additionalProperties", false);

                if (obj.has("properties") && obj.get("properties").isObject()) {
                    ObjectNode propertiesNode = (ObjectNode) obj.get("properties");

                    Set<String> originalRequired = new HashSet<>();
                    if (obj.has("required") && obj.get("required").isArray()) {
                        for (JsonNode req : obj.get("required")) {
                            if (req.isTextual()) {
                                originalRequired.add(req.asText());
                            }
                        }
                    }

                    ArrayNode newRequired = obj.putArray("required");
                    Iterator<String> fieldNames = propertiesNode.fieldNames();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        newRequired.add(fieldName);

                        if (!originalRequired.contains(fieldName)) {
                            JsonNode propNode = propertiesNode.get(fieldName);
                            if (propNode != null && propNode.isObject()) {
                                ObjectNode propObj = (ObjectNode) propNode;
                                makeNullable(propObj);
                            }
                        }
                    }
                }
            }

            if (obj.has("properties") && obj.get("properties").isObject()) {
                obj.get("properties").fields().forEachRemaining(entry -> transformSchemaForOpenAiStrict(entry.getValue()));
            }
            if (obj.has("items")) {
                transformSchemaForOpenAiStrict(obj.get("items"));
            }
        }
    }

    private static void makeNullable(ObjectNode propObj) {
        if (propObj.has("type")) {
            JsonNode typeNode = propObj.get("type");
            if (typeNode.isTextual()) {
                String origType = typeNode.asText();
                if (!"null".equalsIgnoreCase(origType)) {
                    ArrayNode typeArray = propObj.putArray("type");
                    typeArray.add(origType).add("null");
                }
            } else if (typeNode.isArray()) {
                ArrayNode typeArray = (ArrayNode) typeNode;
                boolean hasNull = false;
                for (JsonNode t : typeArray) {
                    if (t.isTextual() && "null".equalsIgnoreCase(t.asText())) {
                        hasNull = true;
                        break;
                    }
                }
                if (!hasNull) {
                    typeArray.add("null");
                }
            }
        }
        if (propObj.has("enum") && propObj.get("enum").isArray()) {
            ArrayNode enumArray = (ArrayNode) propObj.get("enum");
            boolean hasNull = false;
            for (JsonNode e : enumArray) {
                if (e.isNull()) {
                    hasNull = true;
                    break;
                }
            }
            if (!hasNull) {
                enumArray.addNull();
            }
        }
    }
}
