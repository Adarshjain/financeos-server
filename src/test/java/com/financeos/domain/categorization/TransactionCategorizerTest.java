package com.financeos.domain.categorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionCategorizerTest {

    private static final Pattern INDEX_PATTERN = Pattern.compile("Index: (\\d+)");

    private static class FakeLlmClient implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();
        int recommendedBatchSize = 50;
        String defaultProviderId = "fake";
        final Map<Integer, String> providerIdByCall = new HashMap<>();
        final Map<String, Integer> batchSizeByProvider = new HashMap<>();
        final Set<Integer> failOnCall = new HashSet<>();
        int callCount = 0;

        @Override
        public LlmResponse complete(LlmRequest request) {
            callCount++;
            requests.add(request);
            if (failOnCall.contains(callCount)) {
                throw new LlmException(LlmException.Kind.FATAL, "fake", null, null, "Simulated failure on call " + callCount);
            }

            List<Integer> indexes = new ArrayList<>();
            Matcher matcher = INDEX_PATTERN.matcher(request.prompt());
            while (matcher.find()) {
                indexes.add(Integer.parseInt(matcher.group(1)));
            }

            StringBuilder json = new StringBuilder("{\"results\":[");
            for (int i = 0; i < indexes.size(); i++) {
                int idx = indexes.get(i);
                if (i > 0) {
                    json.append(",");
                }
                json.append("{\"index\":").append(idx)
                        .append(",\"merchantKey\":\"MERCHANT-").append(idx).append("\"")
                        .append(",\"displayName\":\"Display-").append(idx).append("\"")
                        .append(",\"categoryNames\":[\"Cat-").append(idx).append("\"]")
                        .append(",\"noFit\":false}");
            }
            json.append("]}");

            String responseProviderId = providerIdByCall.getOrDefault(callCount, defaultProviderId);
            return new LlmResponse(json.toString(), responseProviderId, "fake-model");
        }

        @Override
        public int recommendedBatchSize(String task) {
            return recommendedBatchSize;
        }

        @Override
        public int batchSizeOf(String providerId) {
            return batchSizeByProvider.getOrDefault(providerId, 50);
        }
    }

    private static String uniqueSuffix(int i) {
        StringBuilder sb = new StringBuilder();
        int n = i;
        do {
            sb.append((char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.reverse().toString();
    }

    private static List<TransactionCategorizer.CategorizeItemRequest> uniqueItems(int count) {
        List<TransactionCategorizer.CategorizeItemRequest> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new TransactionCategorizer.CategorizeItemRequest(i, "MERCHANT " + uniqueSuffix(i)));
        }
        return items;
    }

    private static int countIndexes(LlmRequest request) {
        Matcher matcher = INDEX_PATTERN.matcher(request.prompt());
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Test
    public void testDedupeGroupsByNormalizedDescription() {
        FakeLlmClient fake = new FakeLlmClient();
        TransactionCategorizer categorizer = new TransactionCategorizer(fake, new ObjectMapper());

        String[] descriptions = {
                "SWIGGY BANGALORE 123456", "SWIGGY BANGALORE 654321", "SWIGGY BANGALORE 111222",
                "AMAZON PAY INDIA 998877", "AMAZON PAY INDIA 112233", "AMAZON PAY INDIA 334455", "AMAZON PAY INDIA 556677",
                "UBER TRIP 999000", "UBER TRIP 111000", "UBER TRIP 222000"
        };
        List<TransactionCategorizer.CategorizeItemRequest> items = new ArrayList<>();
        for (int i = 0; i < descriptions.length; i++) {
            items.add(new TransactionCategorizer.CategorizeItemRequest(i, descriptions[i]));
        }

        List<TransactionCategorizer.CategorizeItemResponse> results =
                categorizer.categorize(items, List.of("Food", "Shopping", "Travel"));

        assertEquals(1, fake.requests.size());
        assertEquals(3, countIndexes(fake.requests.get(0)));
        assertEquals(10, results.size());

        Map<Integer, TransactionCategorizer.CategorizeItemResponse> byIndex = new HashMap<>();
        for (TransactionCategorizer.CategorizeItemResponse r : results) {
            byIndex.put(r.index(), r);
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of("Cat-0"), byIndex.get(i).categoryNames());
        }
        for (int i = 3; i < 7; i++) {
            assertEquals(List.of("Cat-3"), byIndex.get(i).categoryNames());
        }
        for (int i = 7; i < 10; i++) {
            assertEquals(List.of("Cat-7"), byIndex.get(i).categoryNames());
        }
    }

    @Test
    public void testChunkingSplitsByRecommendedBatchSize() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.recommendedBatchSize = 50;
        TransactionCategorizer categorizer = new TransactionCategorizer(fake, new ObjectMapper());

        List<TransactionCategorizer.CategorizeItemRequest> items = uniqueItems(120);
        List<TransactionCategorizer.CategorizeItemResponse> results = categorizer.categorize(items, List.of("Food"));

        assertEquals(3, fake.requests.size());
        assertEquals(50, countIndexes(fake.requests.get(0)));
        assertEquals(50, countIndexes(fake.requests.get(1)));
        assertEquals(20, countIndexes(fake.requests.get(2)));
        assertEquals(120, results.size());
    }

    @Test
    public void testAdaptiveBatchSizeFromProviderServingChunk() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.recommendedBatchSize = 50;
        fake.providerIdByCall.put(1, "groq");
        fake.batchSizeByProvider.put("groq", 10);
        TransactionCategorizer categorizer = new TransactionCategorizer(fake, new ObjectMapper());

        List<TransactionCategorizer.CategorizeItemRequest> items = uniqueItems(65);
        List<TransactionCategorizer.CategorizeItemResponse> results = categorizer.categorize(items, List.of("Food"));

        assertEquals(3, fake.requests.size());
        assertEquals(50, countIndexes(fake.requests.get(0)));
        assertEquals(10, countIndexes(fake.requests.get(1)));
        assertEquals(5, countIndexes(fake.requests.get(2)));
        assertEquals(65, results.size());
    }

    @Test
    public void testChunkFailureIsIsolatedFromOtherChunks() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.recommendedBatchSize = 10;
        fake.failOnCall.add(1); // first attempt at chunk A (indexes 0-9)
        fake.failOnCall.add(2); // sub-chunk retry of chunk A also fails
        TransactionCategorizer categorizer = new TransactionCategorizer(fake, new ObjectMapper());

        List<TransactionCategorizer.CategorizeItemRequest> items = uniqueItems(20);
        List<TransactionCategorizer.CategorizeItemResponse> results = categorizer.categorize(items, List.of("Food"));

        assertEquals(3, fake.requests.size());
        assertEquals(10, results.size());

        Set<Integer> resultIndexes = new HashSet<>();
        for (TransactionCategorizer.CategorizeItemResponse r : results) {
            resultIndexes.add(r.index());
        }
        for (int i = 0; i < 10; i++) {
            assertFalse(resultIndexes.contains(i), "index " + i + " from failed chunk A should be absent");
        }
        for (int i = 10; i < 20; i++) {
            assertTrue(resultIndexes.contains(i), "index " + i + " from chunk B should be present");
        }
    }

    @Test
    public void testChunkFailureRetriesInSubChunksAndRecovers() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.recommendedBatchSize = 50;
        fake.failOnCall.add(1); // the whole 50-item chunk fails once
        TransactionCategorizer categorizer = new TransactionCategorizer(fake, new ObjectMapper());

        List<TransactionCategorizer.CategorizeItemRequest> items = uniqueItems(50);
        List<TransactionCategorizer.CategorizeItemResponse> results = categorizer.categorize(items, List.of("Food"));

        // 1 failed full-size attempt + 3 successful sub-chunk retries (20, 20, 10)
        assertEquals(4, fake.requests.size());
        assertEquals(20, countIndexes(fake.requests.get(1)));
        assertEquals(20, countIndexes(fake.requests.get(2)));
        assertEquals(10, countIndexes(fake.requests.get(3)));
        assertEquals(50, results.size());

        Set<Integer> resultIndexes = new HashSet<>();
        for (TransactionCategorizer.CategorizeItemResponse r : results) {
            resultIndexes.add(r.index());
        }
        for (int i = 0; i < 50; i++) {
            assertTrue(resultIndexes.contains(i));
        }
    }
}
