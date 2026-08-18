package com.financeos.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityMetricsTest {

    private MeterRegistry registry;
    private ObservabilityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(registry);
    }

    @Test
    void testRecordJobSuccessUpdatesGauge() {
        metrics.recordJobSuccess("gmail-ingest");

        Gauge gauge = registry.find("financeos.job.last.success.timestamp").tag("job", "gmail-ingest").gauge();
        assertNotNull(gauge, "financeos.job.last.success.timestamp gauge must exist for gmail-ingest");
        assertTrue(gauge.value() > 0, "Gauge value must be positive epoch second");
    }

    @Test
    void testRecordLlmAttemptIncrementsCounter() {
        metrics.recordLlmAttempt("gemini", "success");
        metrics.recordLlmAttempt("gemini", "success");
        metrics.recordLlmAttempt("cerebras", "rate_limit");

        Counter geminiSuccess = registry.find("financeos.llm.attempts")
                .tag("provider", "gemini")
                .tag("outcome", "success")
                .counter();
        assertNotNull(geminiSuccess);
        assertEquals(2.0, geminiSuccess.count());

        Counter cerebrasLimit = registry.find("financeos.llm.attempts")
                .tag("provider", "cerebras")
                .tag("outcome", "rate_limit")
                .counter();
        assertNotNull(cerebrasLimit);
        assertEquals(1.0, cerebrasLimit.count());
    }

    @Test
    void testRecordLlmTokensIncrementsCounter() {
        metrics.recordLlmTokens("groq", "prompt", 150);
        metrics.recordLlmTokens("groq", "completion", 75);

        Counter promptTokens = registry.find("financeos.llm.tokens")
                .tag("provider", "groq")
                .tag("kind", "prompt")
                .counter();
        assertNotNull(promptTokens);
        assertEquals(150.0, promptTokens.count());

        Counter completionTokens = registry.find("financeos.llm.tokens")
                .tag("provider", "groq")
                .tag("kind", "completion")
                .counter();
        assertNotNull(completionTokens);
        assertEquals(75.0, completionTokens.count());
    }
}
