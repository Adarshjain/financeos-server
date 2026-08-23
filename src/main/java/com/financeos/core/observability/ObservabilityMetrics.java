package com.financeos.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core observability metrics component managing custom FinanceOS Prometheus meters.
 */
@Component
public class ObservabilityMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> jobLastSuccessGauges = new ConcurrentHashMap<>();

    public ObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void initJobGauges() {
        long bootTime = Instant.now().getEpochSecond();
        getOrCreateJobGauge("gmail-ingest", bootTime);
        getOrCreateJobGauge("price-refresh", bootTime);
    }

    /**
     * Records successful execution timestamp (epoch seconds) for a scheduled job.
     * @param jobName bounded job identifier (e.g. "gmail-ingest", "price-refresh")
     */
    public void recordJobSuccess(String jobName) {
        long currentEpochSecond = Instant.now().getEpochSecond();
        getOrCreateJobGauge(jobName, currentEpochSecond).set(currentEpochSecond);
    }

    /**
     * Increments job failure counter for a background job.
     * @param jobName bounded job identifier
     */
    public void recordJobFailure(String jobName) {
        Counter.builder("financeos.job.failures")
                .tag("job", jobName != null ? jobName : "unknown")
                .register(registry)
                .increment();
    }

    private AtomicLong getOrCreateJobGauge(String jobName, long initialEpochSecond) {
        return jobLastSuccessGauges.computeIfAbsent(jobName, name -> {
            AtomicLong gaugeValue = new AtomicLong(initialEpochSecond);
            Gauge.builder("financeos.job.last.success.timestamp", gaugeValue, AtomicLong::doubleValue)
                    .baseUnit("seconds")
                    .tag("job", name)
                    .register(registry);
            return gaugeValue;
        });
    }

    /**
     * Increments total LLM call attempt count.
     * @param provider LLM provider name (e.g. "gemini", "cerebras", "groq", "openrouter")
     * @param outcome attempt outcome (e.g. "success", "rate_limit", "error", "timeout")
     */
    public void recordLlmAttempt(String provider, String outcome) {
        Counter.builder("financeos.llm.attempts")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("outcome", outcome != null ? outcome : "unknown")
                .register(registry)
                .increment();
    }

    /**
     * Increments total LLM token usage.
     * @param provider LLM provider name
     * @param kind token kind ("prompt" or "completion")
     * @param tokenCount number of tokens consumed
     */
    public void recordLlmTokens(String provider, String kind, long tokenCount) {
        if (tokenCount <= 0) {
            return;
        }
        Counter.builder("financeos.llm.tokens")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("kind", kind != null ? kind : "unknown")
                .register(registry)
                .increment(tokenCount);
    }

    public void recordGmailDiscovered(int count) {
        if (count > 0) {
            Counter.builder("financeos.gmail.discovered").register(registry).increment(count);
        }
    }

    public void recordGmailProcessed(int count) {
        if (count > 0) {
            Counter.builder("financeos.gmail.processed").register(registry).increment(count);
        }
    }

    public void recordGmailRetried(int count) {
        if (count > 0) {
            Counter.builder("financeos.gmail.retried").register(registry).increment(count);
        }
    }
}
