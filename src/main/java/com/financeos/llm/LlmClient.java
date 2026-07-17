package com.financeos.llm;

public interface LlmClient {
    LlmResponse complete(LlmRequest request);

    default int recommendedBatchSize(String task) {
        return 50;
    }

    default int batchSizeOf(String providerId) {
        return 50;
    }
}
