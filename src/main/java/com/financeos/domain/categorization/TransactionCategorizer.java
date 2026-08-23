package com.financeos.domain.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class TransactionCategorizer {

    private static final int RETRY_CHUNK_SIZE = 20;

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

    private record ChunkResult(List<CategorizeItemResponse> responses, String providerId) {}

    public List<CategorizeItemResponse> categorize(List<CategorizeItemRequest> items, List<String> availableCategories) {
        List<CategorizeItemResponse> fallbackResults = new ArrayList<>();

        if (items == null || items.isEmpty()) {
            return fallbackResults;
        }

        List<String> workingCategories = availableCategories != null ? new ArrayList<>(availableCategories) : new ArrayList<>();

        List<CategorizeItemRequest> representatives;
        Map<Integer, List<Integer>> groupMembers;
        try {
            Map<String, Integer> keyToRepresentativeIndex = new LinkedHashMap<>();
            Map<Integer, CategorizeItemRequest> representativeByIndex = new LinkedHashMap<>();
            groupMembers = new LinkedHashMap<>();

            for (CategorizeItemRequest item : items) {
                String normalized = DescriptionNormalizer.normalize(item.description());
                String key = normalized.isBlank() ? item.description() : normalized;

                Integer repIndex = keyToRepresentativeIndex.get(key);
                if (repIndex == null) {
                    keyToRepresentativeIndex.put(key, item.index());
                    representativeByIndex.put(item.index(), item);
                    List<Integer> members = new ArrayList<>();
                    members.add(item.index());
                    groupMembers.put(item.index(), members);
                } else {
                    groupMembers.get(repIndex).add(item.index());
                }
            }

            representatives = new ArrayList<>(representativeByIndex.values());
        } catch (Exception e) {
            log.error("Failed to prepare categorization batches", e);
            return fallbackResults;
        }

        List<CategorizeItemResponse> fanOutResults = new ArrayList<>();
        int size = llmClient.recommendedBatchSize("categorize");
        int i = 0;
        while (i < representatives.size()) {
            int chunkSize = Math.min(size, representatives.size() - i);
            List<CategorizeItemRequest> chunk = representatives.subList(i, i + chunkSize);
            i += chunkSize;

            List<CategorizeItemResponse> chunkResponses;
            try {
                ChunkResult result = categorizeChunk(chunk, workingCategories);
                log.info("Categorized chunk of {} items via provider {}", chunk.size(), result.providerId());
                size = llmClient.batchSizeOf(result.providerId());
                chunkResponses = result.responses();
                updateWorkingCategories(chunkResponses, workingCategories);
            } catch (Exception e) {
                log.warn("Chunk of {} items failed categorization, retrying in sub-chunks of {}: {}",
                        chunk.size(), RETRY_CHUNK_SIZE, e.getMessage());
                chunkResponses = retryChunkInSubChunks(chunk, workingCategories);
            }

            for (CategorizeItemResponse response : chunkResponses) {
                emitFanOut(response, groupMembers, fanOutResults);
            }
        }

        return fanOutResults;
    }

    private List<CategorizeItemResponse> retryChunkInSubChunks(List<CategorizeItemRequest> chunk, List<String> workingCategories) {
        List<CategorizeItemResponse> results = new ArrayList<>();
        int i = 0;
        while (i < chunk.size()) {
            int subSize = Math.min(RETRY_CHUNK_SIZE, chunk.size() - i);
            List<CategorizeItemRequest> subChunk = chunk.subList(i, i + subSize);
            i += subSize;

            try {
                ChunkResult result = categorizeChunk(subChunk, workingCategories);
                log.info("Categorized retry sub-chunk of {} items via provider {}", subChunk.size(), result.providerId());
                updateWorkingCategories(result.responses(), workingCategories);
                results.addAll(result.responses());
            } catch (Exception e) {
                log.warn("Sub-chunk of {} items failed categorization after retry, dropping: {}", subChunk.size(), e.getMessage());
            }
        }
        return results;
    }

    private void updateWorkingCategories(List<CategorizeItemResponse> responses, List<String> workingCategories) {
        if (responses == null) {
            return;
        }
        for (CategorizeItemResponse response : responses) {
            if (response.categoryNames() != null) {
                for (String catName : response.categoryNames()) {
                    if (catName != null && !catName.isBlank()) {
                        boolean exists = workingCategories.stream().anyMatch(c -> c.equalsIgnoreCase(catName));
                        if (!exists) {
                            workingCategories.add(catName);
                        }
                    }
                }
            }
        }
    }

    private void emitFanOut(CategorizeItemResponse response, Map<Integer, List<Integer>> groupMembers, List<CategorizeItemResponse> out) {
        if (response.index() == null) {
            return;
        }
        List<Integer> members = groupMembers.get(response.index());
        if (members == null) {
            return;
        }
        for (Integer memberIndex : members) {
            out.add(new CategorizeItemResponse(memberIndex, response.merchantKey(), response.displayName(), response.categoryNames(), response.noFit()));
        }
    }

    private ChunkResult categorizeChunk(List<CategorizeItemRequest> items, List<String> availableCategories) throws Exception {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are a transaction categorization assistant.\n");
        if (availableCategories == null || availableCategories.isEmpty()) {
            promptBuilder.append("The user has no categories yet. Invent a small set of broad, reusable spending categories and assign each transaction to them. Category names must be a single Title Case word (e.g. Food, Travel, Utilities, Fuel, Shopping); use two words only when a single word cannot describe the category.\n\n");
        } else {
            promptBuilder.append("Associate each of the following transaction descriptions with one or more categories from the existing category list. Prefer an existing category; only when none genuinely applies, propose ONE new category name (must be a broad, reusable spending category: a single Title Case word, e.g. Food, Travel, Utilities, Fuel; two words only when a single word cannot describe it - NEVER a merchant name).\n\n");
            promptBuilder.append("Existing Categories:\n");
            for (String cat : availableCategories) {
                promptBuilder.append("- ").append(cat).append("\n");
            }
            promptBuilder.append("\n");
        }
        promptBuilder.append("Transactions to categorize:\n");
        for (CategorizeItemRequest item : items) {
            promptBuilder.append("Index: ").append(item.index()).append(", Description: ").append(item.description()).append("\n");
        }
        promptBuilder.append("\nReturn a JSON object with a 'results' array where each item contains:\n");
        promptBuilder.append("- index: the index of the transaction\n");
        promptBuilder.append("- merchantKey: the merchant/payee identifier substring as it appears in the description (e.g. SWIGGY, AMAZON). This seeds a rule that auto-matches future transactions containing it, so it must be the stable part only - never include transaction-specific parts like reference/order numbers, dates, amounts, or UPI handles' numeric prefixes. For person-to-person transfers use the person's name.\n");
        promptBuilder.append("- displayName: a clean human-readable name for the merchant or payee (e.g. Swiggy, Amazon)\n");
        promptBuilder.append("- categoryNames: array of category names (existing, or newly proposed broad categories of at most two words) that genuinely apply. Never filler.\n");
        promptBuilder.append("- noFit: boolean, set to true if the transaction description is unintelligible or cannot be classified at all. When unsure, prefer noFit over guessing a category.\n");

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

        log.info("Calling LLM to categorize batch of {} items", items.size());
        LlmResponse response = llmClient.complete(request);
        String jsonText = response.jsonText();
        log.debug("LLM returned JSON text: {}", jsonText);

        JsonNode resultsNode = objectMapper.readTree(jsonText).get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            throw new IllegalStateException("Invalid response format, 'results' array is missing: " + jsonText);
        }

        List<CategorizeItemResponse> results = new ArrayList<>();
        for (JsonNode node : resultsNode) {
            CategorizeItemResponse itemRes = objectMapper.treeToValue(node, CategorizeItemResponse.class);
            results.add(itemRes);
        }
        return new ChunkResult(results, response.providerId());
    }
}
