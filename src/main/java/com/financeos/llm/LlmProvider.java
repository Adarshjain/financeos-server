package com.financeos.llm;

public interface LlmProvider {
    String id();
    LlmResponse complete(LlmRequest request);
}
