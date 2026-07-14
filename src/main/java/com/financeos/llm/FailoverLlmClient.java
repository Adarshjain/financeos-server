package com.financeos.llm;

import com.financeos.llm.provider.LlmHttpSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FailoverLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final LlmProperties properties;
    private final Map<String, LlmProvider> providers;
    private final ConcurrentHashMap<String, CircuitState> circuitStates = new ConcurrentHashMap<>();

    private static class CircuitState {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicLong lastFailureTimestamp = new AtomicLong(0);
    }

    public FailoverLlmClient(LlmProperties properties, Map<String, LlmProvider> providers) {
        this.properties = properties;
        this.providers = providers != null ? providers : new HashMap<>();
        validateChainsAtStartup();
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

        List<String> rawChain = resolveChain(task);
        if (rawChain.isEmpty()) {
            throw new LlmException(LlmException.Kind.FATAL, "none", null, null, "No LLM chain configured for task: " + task);
        }

        List<String> eligibleChain = new ArrayList<>();
        for (String id : rawChain) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && isEligible(trimmed)) {
                eligibleChain.add(trimmed);
            }
        }

        if (eligibleChain.isEmpty()) {
            throw new LlmException(LlmException.Kind.FATAL, "none", null, null, "All providers in chain are skipped or have no API key for task: " + task);
        }

        boolean allOpen = eligibleChain.stream().allMatch(this::isCircuitOpen);
        boolean ignoreBreaker = allOpen;

        List<String> failureMessages = new ArrayList<>();

        int attemptsPerProvider = properties.getRetry().getAttemptsPerProvider();
        long baseDelay = properties.getRetry().getBaseDelayMs();
        long maxDelay = properties.getRetry().getMaxDelayMs();

        for (String providerId : eligibleChain) {
            if (!ignoreBreaker && isCircuitOpen(providerId)) {
                log.info("Skipping provider {} for task {} because circuit breaker is open", providerId, task);
                failureMessages.add(providerId + ": circuit breaker open");
                continue;
            }

            LlmProvider provider = providers.get(providerId);
            if (provider == null) {
                failureMessages.add(providerId + ": provider not found");
                continue;
            }

            String model = getModel(providerId);

            for (int attempt = 1; attempt <= attemptsPerProvider; attempt++) {
                long startTime = System.currentTimeMillis();
                try {
                    LlmResponse response = provider.complete(request);
                    long latencyMs = System.currentTimeMillis() - startTime;
                    log.info("LLM attempt task={}, provider={}, model={}, attempt={}, outcome=SUCCESS, latency={}ms",
                            task, providerId, model, attempt, latencyMs);
                    recordSuccess(providerId);
                    return response;
                } catch (LlmException e) {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    String outcome = e.getStatusCode() != null ? String.valueOf(e.getStatusCode()) : e.getKind().name();
                    log.info("LLM attempt task={}, provider={}, model={}, attempt={}, outcome={}, latency={}ms",
                            task, providerId, model, attempt, outcome, latencyMs);

                    if (e.getKind() == LlmException.Kind.FATAL || e.getKind() == LlmException.Kind.BAD_OUTPUT) {
                        recordFailure(providerId);
                        failureMessages.add(providerId + ": " + (e.getMessage() != null ? e.getMessage() : e.getKind().name()));
                        break;
                    }

                    // RETRYABLE
                    if (attempt < attemptsPerProvider) {
                        long delay = calculateBackoff(attempt - 1, baseDelay, maxDelay, e.getRetryAfterSeconds());
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Interrupted during retry backoff", ie);
                        }
                    } else {
                        recordFailure(providerId);
                        failureMessages.add(providerId + ": " + (e.getMessage() != null ? e.getMessage() : e.getKind().name()));
                    }
                } catch (Exception e) {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    log.info("LLM attempt task={}, provider={}, model={}, attempt={}, outcome=ERROR, latency={}ms",
                            task, providerId, model, attempt, latencyMs);
                    recordFailure(providerId);
                    failureMessages.add(providerId + ": " + e.getMessage());
                    break;
                }
            }
        }

        String chainMessage = "All providers failed for task '" + task + "': " + String.join("; ", failureMessages);
        throw new LlmException(LlmException.Kind.FATAL, "chain", null, null,
                LlmHttpSupport.truncate(chainMessage, 1500));
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

    private boolean isEligible(String providerId) {
        if (properties != null && properties.getProviders() != null && properties.getProviders().containsKey(providerId)) {
            LlmProperties.ProviderProperties prop = properties.getProviders().get(providerId);
            if (prop != null) {
                boolean hasKey = prop.getApiKey() != null && !prop.getApiKey().trim().isEmpty();
                return hasKey || prop.isAllowNoKey();
            }
        }
        return true;
    }

    private String getModel(String providerId) {
        if (properties != null && properties.getProviders() != null && properties.getProviders().containsKey(providerId)) {
            LlmProperties.ProviderProperties prop = properties.getProviders().get(providerId);
            if (prop != null && prop.getModel() != null) {
                return prop.getModel();
            }
        }
        return "unknown";
    }

    private boolean isCircuitOpen(String providerId) {
        CircuitState state = circuitStates.get(providerId);
        if (state == null) {
            return false;
        }
        long cooldownMs = properties.getRetry().getCooldownMs();
        if (state.consecutiveFailures.get() >= 3) {
            long elapsed = System.currentTimeMillis() - state.lastFailureTimestamp.get();
            return elapsed < cooldownMs;
        }
        return false;
    }

    private void recordSuccess(String providerId) {
        CircuitState state = circuitStates.computeIfAbsent(providerId, k -> new CircuitState());
        state.consecutiveFailures.set(0);
        state.lastFailureTimestamp.set(0);
    }

    private void recordFailure(String providerId) {
        CircuitState state = circuitStates.computeIfAbsent(providerId, k -> new CircuitState());
        state.consecutiveFailures.incrementAndGet();
        state.lastFailureTimestamp.set(System.currentTimeMillis());
    }

    private long calculateBackoff(int retryIndex, long baseDelay, long maxDelay, Long retryAfterSeconds) {
        long exponentialDelay = baseDelay * (1L << Math.min(retryIndex, 30));
        long cappedDelay = Math.min(maxDelay, exponentialDelay);
        long jitteredDelay = ThreadLocalRandom.current().nextLong(0, cappedDelay + 1);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            long retryAfterMs = Math.min(retryAfterSeconds * 1000L, maxDelay);
            if (retryAfterMs > jitteredDelay) {
                return retryAfterMs;
            }
        }
        return jitteredDelay;
    }
}
