package com.financeos.llm;

import java.util.UUID;

public interface LlmClient {
    LlmResponse complete(LlmRequest request);

    default int recommendedBatchSize(UUID userId, String task) {
        return recommendedBatchSize(task);
    }

    default int recommendedBatchSize(String task) {
        return 50;
    }

    default int batchSizeOf(String providerId) {
        return 50;
    }
}
