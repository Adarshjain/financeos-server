package com.financeos.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "llm")
@Getter
@Setter
public class LlmProperties {

    private List<String> chain = new ArrayList<>();
    private RetryProperties retry = new RetryProperties();
    private Map<String, ProviderProperties> providers = new HashMap<>();
    private Map<String, TaskProperties> tasks = new HashMap<>();

    @Getter
    @Setter
    public static class RetryProperties {
        private int attemptsPerProvider = 2;
        private long baseDelayMs = 1000L;
        private long maxDelayMs = 8000L;
        private long cooldownMs = 60000L;
    }

    @Getter
    @Setter
    public static class ProviderProperties {
        private String type;
        private String apiKey;
        private String baseUrl;
        private String model;
        private long timeoutMs = 30000L;
        private String structuredOutput = "json-schema";
        private boolean allowNoKey = false;
        private Map<String, String> headers = new HashMap<>();
        private int batchSize = 50;
    }

    @Getter
    @Setter
    public static class TaskProperties {
        private List<String> chain = new ArrayList<>();
    }
}
