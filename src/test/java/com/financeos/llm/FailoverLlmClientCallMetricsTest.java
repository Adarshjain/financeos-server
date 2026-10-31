package com.financeos.llm;

import com.financeos.core.observability.ObservabilityMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** One financeos.llm.calls count per request, tagged by task, however many attempts the chain made. */
class FailoverLlmClientCallMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.setChain(List.of("A", "B"));
        properties.getRetry().setAttemptsPerProvider(1);
        properties.getRetry().setBaseDelayMs(1L);
        properties.getRetry().setMaxDelayMs(5L);
    }

    private static LlmProvider provider(String id, LlmException failure) {
        return new LlmProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public LlmResponse complete(LlmRequest request, String apiKey, String model) {
                if (failure != null) {
                    throw failure;
                }
                return new LlmResponse("{}", id, "m");
            }
        };
    }

    private FailoverLlmClient client(LlmProvider a, LlmProvider b) {
        return new FailoverLlmClient(properties, Map.of("A", a, "B", b), null, null, new ObservabilityMetrics(registry));
    }

    private double calls(String task, String outcome) {
        Counter c = registry.find("financeos.llm.calls").tag("task", task).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void successAfterFailoverCountsOneCallForTheTask() {
        LlmException down = new LlmException(LlmException.Kind.RETRYABLE, "A", 503, null, "503");
        client(provider("A", down), provider("B", null)).complete(new LlmRequest("categorize", "p", null, 0.0));

        assertEquals(1.0, calls("categorize", "success"));
        assertEquals(0.0, calls("categorize", "exhausted"));
    }

    @Test
    void exhaustedChainCountsOneExhaustedCall() {
        LlmException down = new LlmException(LlmException.Kind.RETRYABLE, "x", 503, null, "503");
        FailoverLlmClient client = client(provider("A", down), provider("B", down));

        assertThrows(LlmException.class, () -> client.complete(new LlmRequest("email-extract", "p", null, 0.0)));
        assertEquals(1.0, calls("email-extract", "exhausted"));
        assertEquals(0.0, calls("email-extract", "success"));
    }

    @Test
    void tasksAreCountedSeparately() {
        FailoverLlmClient client = client(provider("A", null), provider("B", null));
        client.complete(new LlmRequest("categorize", "p", null, 0.0));
        client.complete(new LlmRequest("categorize", "p", null, 0.0));
        client.complete(new LlmRequest("data-chat", "p", null, 0.0));

        assertEquals(2.0, calls("categorize", "success"));
        assertEquals(1.0, calls("data-chat", "success"));
    }

    @Test
    void missingTaskIsTaggedUnknown() {
        new ObservabilityMetrics(registry).recordLlmCall(null, "success");
        new ObservabilityMetrics(registry).recordLlmCall("", "success");

        assertEquals(2.0, calls("unknown", "success"));
    }
}
