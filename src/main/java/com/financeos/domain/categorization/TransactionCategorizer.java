package com.financeos.domain.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class TransactionCategorizer {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public TransactionCategorizer(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
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

            // responseSchema in standard JSON Schema (lowercase types)
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode schemaProperties = schema.putObject("properties");
            ObjectNode resultsSchema = schemaProperties.putObject("results");
            resultsSchema.put("type", "array");

            ObjectNode itemSchema = resultsSchema.putObject("items");
            itemSchema.put("type", "object");
            ObjectNode itemProperties = itemSchema.putObject("properties");

            itemProperties.putObject("index").put("type", "integer");
            itemProperties.putObject("merchantKey").put("type", "string");
            itemProperties.putObject("displayName").put("type", "string");

            ObjectNode categoryNamesSchema = itemProperties.putObject("categoryNames");
            categoryNamesSchema.put("type", "array");
            categoryNamesSchema.putObject("items").put("type", "string");

            itemProperties.putObject("noFit").put("type", "boolean");

            itemSchema.putArray("required")
                    .add("index")
                    .add("merchantKey")
                    .add("categoryNames")
                    .add("noFit");

            schema.putArray("required").add("results");

            LlmRequest request = new LlmRequest("categorize", prompt, schema, 0.0);

            log.info("Calling Gemini API to categorize batch of {} items", items.size());
            LlmResponse response = llmClient.complete(request);
            String jsonText = response.jsonText();
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

        } catch (LlmException e) {
            log.warn("LLM categorization unavailable: {}", e.getMessage());
            return fallbackResults;
        } catch (Exception e) {
            log.error("Failed to categorize transactions using Gemini", e);
            return fallbackResults;
        }
    }
}
