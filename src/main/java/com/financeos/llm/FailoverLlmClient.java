package com.financeos.llm;

import com.financeos.core.observability.Events;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.core.security.UserContext;
import com.financeos.domain.llm.LlmKey;
import com.financeos.domain.llm.LlmKeyRepository;
import com.financeos.domain.llm.LlmKeyStatus;
import com.financeos.domain.llm.LlmTaskPref;
import com.financeos.domain.llm.LlmTaskPrefRepository;
import com.financeos.llm.provider.LlmHttpSupport;
import com.financeos.llm.security.LlmKeyEncryptionService;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

public class FailoverLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final LlmProperties properties;
    private final Map<String, LlmProvider> providers;
    private final LlmKeyRepository keyRepository;
    private final LlmTaskPrefRepository taskPrefRepository;
    private final LlmKeyEncryptionService encryptionService;
    private final ObservabilityMetrics metrics;
    private final BucketStateRegistry bucketStateRegistry;

    public record ChainEntry(String providerId, String model) {}

    public record BucketTarget(
            LlmKey key,
            String providerId,
            String model,
            String bucketKey
    ) {}

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmTaskPrefRepository taskPrefRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics) {
        this(properties, providers, keyRepository, taskPrefRepository, encryptionService, metrics, new BucketStateRegistry());
    }

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmTaskPrefRepository taskPrefRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics,
                              BucketStateRegistry bucketStateRegistry) {
        this.properties = properties;
        this.providers = providers != null ? providers : new HashMap<>();
        this.keyRepository = keyRepository;
        this.taskPrefRepository = taskPrefRepository;
        this.encryptionService = encryptionService;
        this.metrics = metrics;
        this.bucketStateRegistry = bucketStateRegistry != null ? bucketStateRegistry : new BucketStateRegistry();
        validateChainsAtStartup();
    }

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics) {
        this(properties, providers, keyRepository, null, encryptionService, metrics, new BucketStateRegistry());
    }

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics,
                              BucketStateRegistry bucketStateRegistry) {
        this(properties, providers, keyRepository, null, encryptionService, metrics, bucketStateRegistry);
    }

    public FailoverLlmClient(LlmProperties properties, Map<String, LlmProvider> providers) {
        this(properties, providers, null, null, null, null, new BucketStateRegistry());
    }

    public BucketStateRegistry getBucketStateRegistry() {
        return bucketStateRegistry;
    }

    private void validateChainsAtStartup() {
        if (properties != null) {
            List<String> mainChain = properties.getChain();
            if (mainChain != null) {
                for (String id : mainChain) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty() && !providers.containsKey(trimmed)) {
                        throw new IllegalStateException("Unknown provider ID '" + trimmed + "' in main chain");
                    }
                }
            }
            if (properties.getTasks() != null) {
                for (Map.Entry<String, LlmProperties.TaskProperties> entry : properties.getTasks().entrySet()) {
                    List<String> taskChain = entry.getValue().getChain();
                    if (taskChain != null) {
                        for (String id : taskChain) {
                            String trimmed = id.trim();
                            if (!trimmed.isEmpty() && !providers.containsKey(trimmed)) {
                                throw new IllegalStateException("Unknown provider ID '" + trimmed + "' in task chain for task: " + entry.getKey());
                            }
                        }
                    }
                }
            }
            if (providers.containsKey("gemini")) {
                LlmProperties.ProviderProperties geminiProps = properties.getProviders() != null ? properties.getProviders().get("gemini") : null;
                if (geminiProps != null) {
                    getGeminiModels(geminiProps);
                }
            }
            validateRoutingConfigAtStartup();
        }
    }

    /**
     * A bad option id in {@code default-routing} would expand to nothing and silently fall through
     * to the legacy provider chain — a routing change nobody asked for, visible only as odd model
     * choices in production. Fail the boot instead.
     */
    private void validateRoutingConfigAtStartup() {
        Set<String> optionIds = new HashSet<>();
        if (properties.getRoutingOptions() != null) {
            for (LlmProperties.RoutingOption option : properties.getRoutingOptions()) {
                if (option.getId() == null || option.getId().isBlank()) {
                    throw new IllegalStateException("A routing option is missing its id");
                }
                if (!optionIds.add(option.getId().toLowerCase())) {
                    throw new IllegalStateException("Duplicate routing option id: " + option.getId());
                }
                String provider = option.getProvider() != null ? option.getProvider().trim() : "";
                if (properties.getProviders() == null || !properties.getProviders().containsKey(provider)) {
                    throw new IllegalStateException("Routing option '" + option.getId()
                            + "' names an unconfigured provider: " + option.getProvider());
                }
                if (option.getModels() != null && !option.getModels().isEmpty()
                        && option.getModel() != null && !option.getModel().isBlank()) {
                    throw new IllegalStateException("Routing option '" + option.getId()
                            + "' sets both 'model' and 'models'; a chain and a pinned model are mutually exclusive");
                }
            }
        }

        if (properties.getDefaultRouting() != null) {
            Set<String> groupCodes = Arrays.stream(LlmTaskGroup.values())
                    .map(LlmTaskGroup::getCode)
                    .collect(java.util.stream.Collectors.toSet());
            for (Map.Entry<String, List<String>> entry : properties.getDefaultRouting().entrySet()) {
                if (!groupCodes.contains(entry.getKey())) {
                    throw new IllegalStateException("Unknown task group '" + entry.getKey()
                            + "' in llm.default-routing; expected one of " + groupCodes);
                }
                if (entry.getValue() == null) {
                    continue;
                }
                for (String optionId : entry.getValue()) {
                    if (optionId == null || !optionIds.contains(optionId.trim().toLowerCase())) {
                        throw new IllegalStateException("Unknown routing option '" + optionId
                                + "' in llm.default-routing." + entry.getKey());
                    }
                }
            }
        }
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LlmRequest cannot be null");
        }
        String task = request.task() != null ? request.task() : "";
        UUID userId = request.userId() != null ? request.userId() : UserContext.getCurrentUserId();

        List<ChainEntry> rawChain = resolveChain(userId, task);
        if (rawChain.isEmpty()) {
            throw new LlmException(LlmException.Kind.FATAL, "none", null, null, "No LLM chain configured for task: " + task);
        }

        List<BucketTarget> buckets = buildBucketList(userId, rawChain);

        if (buckets.isEmpty()) {
            throw new LlmException(LlmException.Kind.NO_KEYS, "none", null, null, "No active LLM API keys configured. Add an API key in Settings.");
        }

        List<String> failureMessages = new ArrayList<>();
        int attemptsPerProvider = properties != null && properties.getRetry() != null ? properties.getRetry().getAttemptsPerProvider() : 2;
        long baseDelay = properties != null && properties.getRetry() != null ? properties.getRetry().getBaseDelayMs() : 1000L;
        long maxDelay = properties != null && properties.getRetry() != null ? properties.getRetry().getMaxDelayMs() : 8000L;

        long chainStartTime = System.currentTimeMillis();

        for (BucketTarget target : buckets) {
            String providerId = target.providerId();
            String model = target.model();
            String bucketKey = target.bucketKey();
            LlmKey keyEntity = target.key();

            if (bucketStateRegistry.isCooldown(bucketKey)) {
                log.info("Skipping bucket {} for task {} (cooldown/circuit active)", bucketKey, task);
                failureMessages.add(bucketKey + ": cooldown active");
                continue;
            }

            LlmProvider provider = providers.get(providerId);
            if (provider == null) {
                failureMessages.add(providerId + ": provider not found");
                continue;
            }

            String apiKey = null;
            if (keyEntity != null && encryptionService != null) {
                try {
                    apiKey = encryptionService.decrypt(keyEntity.getKeyCiphertext());
                } catch (Exception e) {
                    log.error("Failed to decrypt key ID {} for provider {}", keyEntity.getId(), providerId, e);
                    failureMessages.add(bucketKey + ": key decryption failed");
                    continue;
                }
            }

            int promptChars = request.prompt() != null ? request.prompt().length() : 0;
            int batchSize = batchSizeOf(providerId);

            for (int attempt = 1; attempt <= attemptsPerProvider; attempt++) {
                long startTime = System.currentTimeMillis();
                try {
                    LlmResponse response = provider.complete(request, apiKey, model);
                    long latencyMs = System.currentTimeMillis() - startTime;
                    int responseChars = response != null && response.jsonText() != null ? response.jsonText().length() : 0;

                    log.info("LLM attempt task={}, provider={}, model={}, keyId={}, attempt={}, outcome=success, latency={}ms",
                            task, providerId, model, keyEntity != null ? keyEntity.getId() : "env", attempt, latencyMs,
                            StructuredArguments.keyValue("event", Events.LLM_ATTEMPT),
                            StructuredArguments.keyValue("task", task),
                            StructuredArguments.keyValue("provider", providerId),
                            StructuredArguments.keyValue("model", model),
                            StructuredArguments.keyValue("keyId", keyEntity != null ? keyEntity.getId().toString() : "env"),
                            StructuredArguments.keyValue("attempt", attempt),
                            StructuredArguments.keyValue("outcome", "success"),
                            StructuredArguments.keyValue("latencyMs", latencyMs),
                            StructuredArguments.keyValue("promptChars", promptChars),
                            StructuredArguments.keyValue("responseChars", responseChars),
                            StructuredArguments.keyValue("batchSize", batchSize));

                    bucketStateRegistry.recordSuccess(bucketKey, providerId);
                    if (keyEntity != null && keyRepository != null) {
                        try {
                            keyEntity.setLastUsedAt(Instant.now());
                            keyRepository.save(keyEntity);
                        } catch (Exception e) {
                            log.warn("Failed to update last_used_at for key {}", keyEntity.getId(), e);
                        }
                    }

                    if (metrics != null) {
                        metrics.recordLlmAttempt(providerId, "success");
                    }
                    return response;

                } catch (LlmException e) {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    Integer statusCode = e.getStatusCode();
                    String outcome = statusCode != null ? String.valueOf(statusCode) : e.getKind().name().toLowerCase();
                    String errorClass = e.getClass().getName();
                    String errorBody = e.getMessage() != null ? LlmHttpSupport.truncate(e.getMessage(), 300) : "";

                    log.info("LLM attempt task={}, provider={}, model={}, keyId={}, attempt={}, outcome={}, latency={}ms",
                            task, providerId, model, keyEntity != null ? keyEntity.getId() : "env", attempt, outcome, latencyMs,
                            StructuredArguments.keyValue("event", Events.LLM_ATTEMPT),
                            StructuredArguments.keyValue("task", task),
                            StructuredArguments.keyValue("provider", providerId),
                            StructuredArguments.keyValue("model", model),
                            StructuredArguments.keyValue("keyId", keyEntity != null ? keyEntity.getId().toString() : "env"),
                            StructuredArguments.keyValue("attempt", attempt),
                            StructuredArguments.keyValue("outcome", outcome),
                            StructuredArguments.keyValue("latencyMs", latencyMs),
                            StructuredArguments.keyValue("httpStatus", statusCode != null ? statusCode : 0),
                            StructuredArguments.keyValue("errorClass", errorClass),
                            StructuredArguments.keyValue("errorBody", errorBody));

                    if (statusCode != null && (statusCode == 401 || statusCode == 403)) {
                        // Invalidate Key in DB
                        log.warn("Marking key {} INVALID due to HTTP {}", keyEntity != null ? keyEntity.getId() : "unknown", statusCode);
                        if (keyEntity != null && keyRepository != null) {
                            try {
                                keyEntity.setStatus(LlmKeyStatus.INVALID);
                                keyRepository.save(keyEntity);
                            } catch (Exception ex) {
                                log.error("Failed to mark key {} invalid", keyEntity.getId(), ex);
                            }
                        }
                        failureMessages.add(bucketKey + ": invalid key (HTTP " + statusCode + ")");
                        break; // Advance to next bucket immediately
                    }

                    if (statusCode != null && statusCode == 429) {
                        // Rate limit -> handle cooldown & advance immediately without same-bucket retry
                        String bodyToPass = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                        bucketStateRegistry.handle429(bucketKey, providerId, model, bodyToPass, e.getRetryAfterSeconds(), metrics);
                        failureMessages.add(bucketKey + ": rate limited (429)");
                        break; // Advance to next bucket immediately
                    }

                    if (e.getKind() == LlmException.Kind.FATAL || e.getKind() == LlmException.Kind.BAD_OUTPUT) {
                        bucketStateRegistry.recordFailure(bucketKey, providerId, metrics);
                        failureMessages.add(bucketKey + ": " + (e.getMessage() != null ? e.getMessage() : e.getKind().name()));
                        break;
                    }

                    // RETRYABLE (e.g. 5xx/IO error)
                    if (attempt < attemptsPerProvider) {
                        long delay = calculateBackoff(attempt - 1, baseDelay, maxDelay, e.getRetryAfterSeconds());
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Interrupted during retry backoff", ie);
                        }
                    } else {
                        bucketStateRegistry.recordFailure(bucketKey, providerId, metrics);
                        failureMessages.add(bucketKey + ": " + (e.getMessage() != null ? e.getMessage() : e.getKind().name()));
                    }

                } catch (Exception e) {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    log.info("LLM attempt error: task={}, provider={}, model={}, keyId={}, error={}",
                            task, providerId, model, keyEntity != null ? keyEntity.getId() : "env", e.getMessage());

                    bucketStateRegistry.recordFailure(bucketKey, providerId, metrics);
                    failureMessages.add(bucketKey + ": " + e.getMessage());
                    break;
                }
            }
        }

        long totalChainLatency = System.currentTimeMillis() - chainStartTime;
        List<String> allBucketKeys = buckets.stream().map(BucketTarget::bucketKey).toList();
        Instant soonestExpiry = bucketStateRegistry.getSoonestCooldownExpiry(allBucketKeys);
        String expiryMsg = soonestExpiry != null ? " (soonest capacity returns at " + soonestExpiry + ")" : "";

        log.error("LLM chain exhausted: task={}, bucketsTried={}, totalLatencyMs={}",
                task, buckets.size(), totalChainLatency,
                StructuredArguments.keyValue("event", Events.LLM_CHAIN_EXHAUSTED),
                StructuredArguments.keyValue("task", task),
                StructuredArguments.keyValue("totalLatencyMs", totalChainLatency),
                StructuredArguments.keyValue("failureMessages", String.join("; ", failureMessages)));

        String chainMessage = "All providers failed for task '" + task + "': " + String.join("; ", failureMessages) + expiryMsg;
        throw new LlmException(LlmException.Kind.FATAL, "chain", null, null, LlmHttpSupport.truncate(chainMessage, 1500));
    }

    @Override
    public int recommendedBatchSize(UUID userId, String task) {
        String t = task != null ? task : "";
        List<ChainEntry> rawChain = resolveChain(userId, t);
        List<BucketTarget> buckets = buildBucketList(userId, rawChain);

        for (BucketTarget target : buckets) {
            if (!bucketStateRegistry.isCooldown(target.bucketKey())) {
                return batchSizeOf(target.providerId());
            }
        }
        return 50;
    }

    @Override
    public int recommendedBatchSize(String task) {
        return recommendedBatchSize(UserContext.getCurrentUserId(), task);
    }

    @Override
    public int batchSizeOf(String providerId) {
        if (properties != null && properties.getProviders() != null && properties.getProviders().containsKey(providerId)) {
            LlmProperties.ProviderProperties prop = properties.getProviders().get(providerId);
            if (prop != null) {
                return prop.getBatchSize();
            }
        }
        return 50;
    }

    /** Visible for tests: the resolved, deduped bucket order a request will actually walk. */
    public List<BucketTarget> buildBucketListForTest(UUID userId, List<ChainEntry> rawChain) {
        return buildBucketList(userId, rawChain);
    }

    private List<BucketTarget> buildBucketList(UUID userId, List<ChainEntry> rawChain) {
        List<BucketTarget> targets = new ArrayList<>();
        if (rawChain == null || rawChain.isEmpty()) {
            return targets;
        }

        Map<String, List<LlmKey>> userKeysByProvider = new HashMap<>();
        if (userId != null && keyRepository != null) {
            List<LlmKey> activeKeys = keyRepository.findByUserIdAndStatusOrderByPositionAsc(userId, LlmKeyStatus.ACTIVE);
            for (LlmKey k : activeKeys) {
                userKeysByProvider.computeIfAbsent(k.getProvider().toLowerCase(), p -> new ArrayList<>()).add(k);
            }
        }

        for (ChainEntry entry : rawChain) {
            String providerId = entry.providerId() != null ? entry.providerId().trim() : "";
            if (providerId.isEmpty() || !providers.containsKey(providerId)) {
                continue;
            }

            LlmProperties.ProviderProperties providerProps = properties != null && properties.getProviders() != null ? properties.getProviders().get(providerId) : null;
            List<LlmKey> keys = userKeysByProvider.getOrDefault(providerId.toLowerCase(), Collections.emptyList());

            if (keys.isEmpty()) {
                if (keyRepository == null || (providerProps != null && providerProps.isAllowNoKey())) {
                    String model = entry.model() != null && !entry.model().isBlank() ? entry.model().trim() : getModel(providerId);
                    targets.add(new BucketTarget(null, providerId, model, providerId + ":default:" + model));
                }
                continue;
            }

            if (entry.model() != null && !entry.model().isBlank()) {
                // Explicit user-specified model collapses Gemini model-major iteration
                String model = entry.model().trim();
                for (LlmKey key : keys) {
                    targets.add(new BucketTarget(key, providerId, model, bucketKeyFor(key.getId(), model)));
                }
            } else if ("gemini".equalsIgnoreCase(providerId)) {
                // Model-major iteration: best model across ALL keys, then next model across all keys
                List<String> models = getGeminiModels(providerProps);
                for (String model : models) {
                    for (LlmKey key : keys) {
                        targets.add(new BucketTarget(key, providerId, model, bucketKeyFor(key.getId(), model)));
                    }
                }
            } else {
                // OpenAI-compatible rotation
                String model = getModel(providerId);
                for (LlmKey key : keys) {
                    targets.add(new BucketTarget(key, providerId, model, bucketKeyFor(key.getId(), model)));
                }
            }
        }
        // Overlapping options (the Flash chain is a subset of the full chain) would otherwise queue
        // the same bucket twice — a wasted attempt, and a second cooldown hit on the same credential.
        Map<String, BucketTarget> deduped = new LinkedHashMap<>();
        for (BucketTarget t : targets) {
            deduped.putIfAbsent(t.bucketKey(), t);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * The one place a cooldown bucket is named. Every caller — the failover loop and the routing
     * health endpoint alike — must go through this, or health silently reports on buckets that
     * never existed. The model is always part of the key so that the same (key, model) pair shares
     * cooldown state whether it was reached via a config chain or a user's explicit routing choice.
     */
    public static String bucketKeyFor(UUID keyId, String model) {
        return keyId + ":" + (model != null ? model : "");
    }

    /**
     * Chain order for the gemini-chain routing option. {@code models} is authoritative so the
     * GEMINI_MODELS env override keeps working; {@code model-catalog} is display metadata and is
     * only consulted when {@code models} is unset.
     */
    private List<String> getGeminiModels(LlmProperties.ProviderProperties providerProps) {
        if (providerProps != null) {
            if (providerProps.getModels() != null && !providerProps.getModels().isEmpty()) {
                return providerProps.getModels();
            }
            if (providerProps.getModelCatalog() != null && !providerProps.getModelCatalog().isEmpty()) {
                return providerProps.getModelCatalog().stream().map(LlmProperties.ModelEntry::getId).toList();
            }
        }
        throw new IllegalStateException("No models configured for Gemini provider");
    }

    public List<ChainEntry> resolveChain(UUID userId, String task) {
        LlmTaskGroup group = LlmTasks.groupOf(task);

        if (userId != null && taskPrefRepository != null) {
            List<LlmTaskPref> groupPrefs = taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(userId, group);
            if (!groupPrefs.isEmpty()) {
                List<ChainEntry> entries = new ArrayList<>();
                for (LlmTaskPref pref : groupPrefs) {
                    entries.addAll(expandOption(pref.getOptionId()));
                }
                if (!entries.isEmpty()) {
                    return entries;
                }
            }
            // Deliberately NO cross-group inheritance. A user who reorders only "Everything else"
            // must not have Chat silently switched to a slow bulk model — Chat falls through to the
            // shipped default order, which leads with the Flash chain for latency.
        }

        List<String> defaultOptionIds = properties != null && properties.getDefaultRouting() != null
                ? properties.getDefaultRouting().get(group.getCode())
                : null;
        if (defaultOptionIds != null && !defaultOptionIds.isEmpty()) {
            List<ChainEntry> entries = new ArrayList<>();
            for (String optionId : defaultOptionIds) {
                entries.addAll(expandOption(optionId));
            }
            if (!entries.isEmpty()) {
                return entries;
            }
        }

        // Last resort for deployments that configure only the legacy provider chains.
        if (properties != null && properties.getTasks() != null && properties.getTasks().containsKey(task)) {
            LlmProperties.TaskProperties taskProps = properties.getTasks().get(task);
            if (taskProps != null && taskProps.getChain() != null && !taskProps.getChain().isEmpty()) {
                return taskProps.getChain().stream().map(p -> new ChainEntry(p.trim(), null)).toList();
            }
        }
        if (properties != null && properties.getChain() != null) {
            return properties.getChain().stream().map(p -> new ChainEntry(p.trim(), null)).toList();
        }
        return Collections.emptyList();
    }

    /**
     * A routing option becomes one chain entry per model, in order. Expanding here — rather than
     * teaching {@code buildBucketList} about option shapes — is what keeps model-major ordering
     * correct: entry order is model order, and each entry then fans out across the user's keys.
     */
    private List<ChainEntry> expandOption(String optionId) {
        LlmProperties.RoutingOption option = findRoutingOption(optionId);
        if (option == null || option.getProvider() == null || option.getProvider().isBlank()) {
            return List.of();
        }
        String provider = option.getProvider().trim();

        if (option.getModels() != null && !option.getModels().isEmpty()) {
            return option.getModels().stream().map(m -> new ChainEntry(provider, m)).toList();
        }
        if (option.getModel() != null && !option.getModel().isBlank()) {
            return List.of(new ChainEntry(provider, option.getModel().trim()));
        }
        // No models pinned: the provider's own list, which is the full-chain option.
        LlmProperties.ProviderProperties props = properties != null && properties.getProviders() != null
                ? properties.getProviders().get(provider)
                : null;
        if (props != null && props.getModels() != null && !props.getModels().isEmpty()) {
            return props.getModels().stream().map(m -> new ChainEntry(provider, m)).toList();
        }
        return List.of(new ChainEntry(provider, null));
    }

    public LlmProperties.RoutingOption findRoutingOption(String optionId) {
        if (optionId == null || properties == null || properties.getRoutingOptions() == null) {
            return null;
        }
        return properties.getRoutingOptions().stream()
                .filter(o -> optionId.trim().equalsIgnoreCase(o.getId()))
                .findFirst()
                .orElse(null);
    }

    public List<ChainEntry> resolveChain(String task) {
        return resolveChain(UserContext.getCurrentUserId(), task);
    }

    /** Visible for tests: the model a provider resolves to when no routing option pins one. */
    String getModelForTest(String providerId) {
        return getModel(providerId);
    }

    private String getModel(String providerId) {
        if (properties != null && properties.getProviders() != null && properties.getProviders().containsKey(providerId)) {
            LlmProperties.ProviderProperties prop = properties.getProviders().get(providerId);
            if (prop != null && prop.getModel() != null && !prop.getModel().isBlank()) {
                return prop.getModel();
            }
        }
        return "unknown";
    }

    private long calculateBackoff(int retryIndex, long baseDelay, long maxDelay, Long retryAfterSeconds) {
        long exponentialDelay = baseDelay * (1L << Math.min(retryIndex, 30));
        long cappedDelay = Math.min(maxDelay, exponentialDelay);
        long jitteredDelay = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, cappedDelay + 1);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            long retryAfterMs = Math.min(retryAfterSeconds * 1000L, maxDelay);
            if (retryAfterMs > jitteredDelay) {
                return retryAfterMs;
            }
        }
        return jitteredDelay;
    }
}
