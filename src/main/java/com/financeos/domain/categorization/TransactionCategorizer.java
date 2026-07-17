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

        if (items == null || items.isEmpty() || availableCategories == null || availableCategories.isEmpty()) {
            return fallbackResults;
        }

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
                ChunkResult result = categorizeChunk(chunk, availableCategories);
                log.info("Categorized chunk of {} items via provider {}", chunk.size(), result.providerId());
                size = llmClient.batchSizeOf(result.providerId());
                chunkResponses = result.responses();
            } catch (Exception e) {
                log.warn("Chunk of {} items failed categorization, retrying in sub-chunks of {}: {}",
                        chunk.size(), RETRY_CHUNK_SIZE, e.getMessage());
                chunkResponses = retryChunkInSubChunks(chunk, availableCategories);
            }

            for (CategorizeItemResponse response : chunkResponses) {
                emitFanOut(response, groupMembers, fanOutResults);
            }
        }

        return fanOutResults;
    }

    private List<CategorizeItemResponse> retryChunkInSubChunks(List<CategorizeItemRequest> chunk, List<String> availableCategories) {
        List<CategorizeItemResponse> results = new ArrayList<>();
        int i = 0;
        while (i < chunk.size()) {
            int subSize = Math.min(RETRY_CHUNK_SIZE, chunk.size() - i);
            List<CategorizeItemRequest> subChunk = chunk.subList(i, i + subSize);
            i += subSize;

            try {
                ChunkResult result = categorizeChunk(subChunk, availableCategories);
                log.info("Categorized retry sub-chunk of {} items via provider {}", subChunk.size(), result.providerId());
                results.addAll(result.responses());
            } catch (Exception e) {
                log.warn("Sub-chunk of {} items failed categorization after retry, dropping: {}", subChunk.size(), e.getMessage());
            }
        }
        return results;
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
