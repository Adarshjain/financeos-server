package com.financeos.llm;

import com.financeos.core.observability.Events;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.core.security.UserContext;
import com.financeos.domain.llm.LlmKey;
import com.financeos.domain.llm.LlmKeyRepository;
import com.financeos.domain.llm.LlmKeyStatus;
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
    private final LlmKeyEncryptionService encryptionService;
    private final ObservabilityMetrics metrics;
    private final BucketStateRegistry bucketStateRegistry;

    public record BucketTarget(
            LlmKey key,
            String providerId,
            String model,
            String bucketKey
    ) {}

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics) {
        this(properties, providers, keyRepository, encryptionService, metrics, new BucketStateRegistry());
    }

    public FailoverLlmClient(LlmProperties properties,
                              Map<String, LlmProvider> providers,
                              LlmKeyRepository keyRepository,
                              LlmKeyEncryptionService encryptionService,
                              ObservabilityMetrics metrics,
                              BucketStateRegistry bucketStateRegistry) {
        this.properties = properties;
        this.providers = providers != null ? providers : new HashMap<>();
        this.keyRepository = keyRepository;
        this.encryptionService = encryptionService;
        this.metrics = metrics;
        this.bucketStateRegistry = bucketStateRegistry != null ? bucketStateRegistry : new BucketStateRegistry();
        validateChainsAtStartup();
    }

    public FailoverLlmClient(LlmProperties properties, Map<String, LlmProvider> providers) {
        this(properties, providers, null, null, null, new BucketStateRegistry());
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
        }
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LlmRequest cannot be null");
        }
        String task = request.task() != null ? request.task() : "";
        UUID userId = request.userId() != null ? request.userId() : UserContext.getCurrentUserId();

        List<String> rawChain = resolveChain(task);
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
        List<String> rawChain = resolveChain(t);
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

    private List<BucketTarget> buildBucketList(UUID userId, List<String> rawChain) {
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

        for (String pId : rawChain) {
            String providerId = pId.trim();
            if (providerId.isEmpty() || !providers.containsKey(providerId)) {
                continue;
            }

            LlmProperties.ProviderProperties providerProps = properties != null && properties.getProviders() != null ? properties.getProviders().get(providerId) : null;
            List<LlmKey> keys = userKeysByProvider.getOrDefault(providerId.toLowerCase(), Collections.emptyList());

            if (keys.isEmpty()) {
                if (keyRepository == null || (providerProps != null && providerProps.isAllowNoKey())) {
                    String model = getModel(providerId);
                    targets.add(new BucketTarget(null, providerId, model, providerId + ":default:" + model));
                }
                continue;
            }

            if ("gemini".equalsIgnoreCase(providerId)) {
                // Model-major iteration: best model across ALL keys, then next model across all keys
                List<String> models = getGeminiModels(providerProps);
                for (String model : models) {
                    for (LlmKey key : keys) {
                        String bucketKey = key.getId() + ":" + model;
                        targets.add(new BucketTarget(key, providerId, model, bucketKey));
                    }
                }
            } else {
                // OpenAI-compatible rotation
                String model = getModel(providerId);
                for (LlmKey key : keys) {
                    String bucketKey = key.getId().toString();
                    targets.add(new BucketTarget(key, providerId, model, bucketKey));
                }
            }
        }
        return targets;
    }

    private List<String> getGeminiModels(LlmProperties.ProviderProperties providerProps) {
        if (providerProps != null && providerProps.getModels() != null && !providerProps.getModels().isEmpty()) {
            return providerProps.getModels();
        }
        return List.of(
                "gemini-3.7-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite"
        );
    }

    private List<String> resolveChain(String task) {
        if (properties != null && properties.getTasks() != null && properties.getTasks().containsKey(task)) {
            LlmProperties.TaskProperties taskProps = properties.getTasks().get(task);
            if (taskProps != null && taskProps.getChain() != null && !taskProps.getChain().isEmpty()) {
                return taskProps.getChain();
            }
        }
        if (properties != null && properties.getChain() != null) {
            return properties.getChain();
        }
        return Collections.emptyList();
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
