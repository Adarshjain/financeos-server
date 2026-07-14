package com.financeos.llm;

public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
