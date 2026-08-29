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
    /**
     * The flat, user-pickable routing menu. Each option is one row a user can order in Settings.
     * This is the ONLY source of routing option ids, labels and pinned models — never hardcode them.
     */
    private List<RoutingOption> routingOptions = new ArrayList<>();

    /**
     * Ordered option ids that a task group falls back through when the user has saved no preference.
     * Keyed by {@link LlmTaskGroup#getCode()} ("chat", "default").
     */
    private Map<String, List<String>> defaultRouting = new HashMap<>();

    /**
     * One selectable routing step, resolved in this order:
     * <ol>
     *   <li>{@code models} non-empty — expand model-major across exactly those, in order.</li>
     *   <li>{@code model} set — one pinned model.</li>
     *   <li>neither — the provider's full {@link ProviderProperties#getModels()} list, or its
     *       single default model for providers that have no list.</li>
     * </ol>
     * A chain-style option (1 or 3) is what lets one free-tier key stack a separate daily allowance
     * per model, so it is a first-class choice rather than an artefact of leaving the model blank.
     */
    @Getter
    @Setter
    public static class RoutingOption {
        private String id;
        private String label;
        private String provider;
        private String model;
        private List<String> models = new ArrayList<>();
        private String notes;
    }

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
        /** Human-readable provider name for the UI. Falls back to a capitalised id when unset. */
        private String label;
        private String apiKey;
        private String baseUrl;
        private String model;
        private List<String> models = new ArrayList<>();
        private List<ModelEntry> modelCatalog = new ArrayList<>();
        private long timeoutMs = 30000L;
        private String structuredOutput = "json-schema";
        private boolean allowNoKey = false;
        private Map<String, String> headers = new HashMap<>();
        private int batchSize = 50;

        public ModelEntry findModel(String modelId) {
            if (modelId == null || modelCatalog == null) {
                return null;
            }
            return modelCatalog.stream()
                    .filter(m -> modelId.equalsIgnoreCase(m.getId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Getter
    @Setter
    public static class ModelEntry {
        private String id;
        private String label;
        private String structuredOutput;
        private boolean free = false;
        private String trainsOnData = "no";
        private String notes;
    }

    @Getter
    @Setter
    public static class TaskProperties {
        private List<String> chain = new ArrayList<>();
    }
}
