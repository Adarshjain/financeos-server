package com.financeos.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.provider.GeminiProvider;
import com.financeos.llm.provider.OpenAiCompatProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean
    public FailoverLlmClient llmClient(LlmProperties properties, ObjectMapper objectMapper) {
        Map<String, LlmProvider> providers = new HashMap<>();
        if (properties.getProviders() != null) {
            for (Map.Entry<String, LlmProperties.ProviderProperties> entry : properties.getProviders().entrySet()) {
                String id = entry.getKey();
                LlmProperties.ProviderProperties config = entry.getValue();
                String type = config.getType();
                if ("gemini".equalsIgnoreCase(type)) {
                    providers.put(id, new GeminiProvider(id, config, objectMapper));
                } else if ("openai".equalsIgnoreCase(type)) {
                    providers.put(id, new OpenAiCompatProvider(id, config, objectMapper));
                } else {
                    throw new IllegalStateException("Unknown LLM provider type: " + type + " for provider id: " + id);
                }
            }
        }
        return new FailoverLlmClient(properties, providers);
    }
}
