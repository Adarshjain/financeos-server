package com.financeos.domain.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.gmail.ingest.gemini.GeminiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class GeminiCategorizer {

    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiCategorizer(GeminiProperties geminiProperties, ObjectMapper objectMapper) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    public record CategorizeItemRequest(int index, String description) {}

    public record CategorizeItemResponse(
            Integer index,
            String merchantKey,
            String displayName,
            List<String> categoryNames,
            Boolean noFit
    ) {}

    public List<CategorizeItemResponse> categorize(List<CategorizeItemRequest> items, List<String> availableCategories) {
        List<CategorizeItemResponse> fallbackResults = new ArrayList<>();

        String apiKey = geminiProperties.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Gemini API key is not configured. Skipping Gemini categorization.");
            return fallbackResults;
        }

        if (items == null || items.isEmpty() || availableCategories == null || availableCategories.isEmpty()) {
            return fallbackResults;
        }

        try {
            // Build the prompt
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are a transaction categorization assistant.\n");
            promptBuilder.append("Associate each of the following transaction descriptions with one or more categories from the allowed category list.\n\n");
            promptBuilder.append("Allowed Categories:\n");
            for (String cat : availableCategories) {
                promptBuilder.append("- ").append(cat).append("\n");
            }
            promptBuilder.append("\nTransactions to categorize:\n");
            for (CategorizeItemRequest item : items) {
                promptBuilder.append("Index: ").append(item.index()).append(", Description: ").append(item.description()).append("\n");
            }
            promptBuilder.append("\nReturn a JSON object with a 'results' array where each item contains:\n");
            promptBuilder.append("- index: the index of the transaction\n");
            promptBuilder.append("- merchantKey: the main merchant identifier string as it appears in the description (e.g. SWIGGY, AMAZON)\n");
            promptBuilder.append("- displayName: a clean human-readable name for the merchant (e.g. Swiggy, Amazon)\n");
            promptBuilder.append("- categoryNames: array of category names chosen from the allowed categories list that genuinely apply. Never filler.\n");
            promptBuilder.append("- noFit: boolean, set to true if no category from the allowed categories list genuinely applies.\n");

            String prompt = promptBuilder.toString();

            // Build request JSON structure matching GeminiExtractor
            ObjectNode requestJson = objectMapper.createObjectNode();
            ObjectNode contentPart = objectMapper.createObjectNode().put("text", prompt);
            requestJson.putArray("contents")
                    .addObject()
                    .putArray("parts")
                    .add(contentPart);

            ObjectNode generationConfig = objectMapper.createObjectNode();
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.0);

            // responseSchema
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "OBJECT");

            ObjectNode schemaProperties = schema.putObject("properties");
            ObjectNode resultsSchema = schemaProperties.putObject("results");
            resultsSchema.put("type", "ARRAY");

            ObjectNode itemSchema = resultsSchema.putObject("items");
            itemSchema.put("type", "OBJECT");
            ObjectNode itemProperties = itemSchema.putObject("properties");

            itemProperties.putObject("index").put("type", "INTEGER");
            itemProperties.putObject("merchantKey").put("type", "STRING");
            itemProperties.putObject("displayName").put("type", "STRING");

            ObjectNode categoryNamesSchema = itemProperties.putObject("categoryNames");
            categoryNamesSchema.put("type", "ARRAY");
            categoryNamesSchema.putObject("items").put("type", "STRING");

            itemProperties.putObject("noFit").put("type", "BOOLEAN");

            itemSchema.putArray("required")
                    .add("index")
                    .add("merchantKey")
                    .add("categoryNames")
                    .add("noFit");

            schema.putArray("required").add("results");

            generationConfig.set("responseSchema", schema);
            requestJson.set("generationConfig", generationConfig);

            String requestBody = objectMapper.writeValueAsString(requestJson);
            String url = String.format(
                    "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                    geminiProperties.getModel(), apiKey
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(geminiProperties.getTimeout() != null ? geminiProperties.getTimeout() : 30000))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.info("Calling Gemini API to categorize batch of {} items", items.size());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API returned error code {}: {}", response.statusCode(), response.body());
                return fallbackResults;
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode textNode = responseJson.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode()) {
                log.error("No text found in Gemini response: {}", response.body());
                return fallbackResults;
            }

            String jsonText = textNode.asText();
            log.debug("Gemini returned JSON text: {}", jsonText);

            JsonNode resultsNode = objectMapper.readTree(jsonText).get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                log.error("Invalid response format, 'results' array is missing: {}", jsonText);
                return fallbackResults;
            }

            List<CategorizeItemResponse> results = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                CategorizeItemResponse itemRes = objectMapper.treeToValue(node, CategorizeItemResponse.class);
                results.add(itemRes);
            }
            return results;

        } catch (Exception e) {
            log.error("Failed to categorize transactions using Gemini", e);
            return fallbackResults;
        }
    }
}
