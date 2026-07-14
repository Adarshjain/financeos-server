package com.financeos.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FailoverLlmClientTest {

    private LlmProperties properties;
    private Map<String, LlmProvider> providers;
    private StubLlmProvider providerA;
    private StubLlmProvider providerB;
    private StubLlmProvider providerC;

    private static class StubLlmProvider implements LlmProvider {
        private final String id;
        private int callCount = 0;
        private LlmResponse nextResponse;
        private LlmException nextException;

        public StubLlmProvider(String id) {
            this.id = id;
        }

        public void setOutcome(LlmResponse response, LlmException exception) {
            this.nextResponse = response;
            this.nextException = exception;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            callCount++;
            if (nextException != null) {
                throw nextException;
            }
            if (nextResponse != null) {
                return nextResponse;
            }
            return new LlmResponse("{}", id, "mock-model");
        }

        public int getCallCount() {
            return callCount;
        }
    }

    @BeforeEach
    public void setUp() {
        properties = new LlmProperties();
        properties.setChain(List.of("A", "B", "C"));
        properties.getRetry().setAttemptsPerProvider(2);
        properties.getRetry().setBaseDelayMs(10L);
        properties.getRetry().setMaxDelayMs(50L);
        properties.getRetry().setCooldownMs(60000L);

        providerA = new StubLlmProvider("A");
        providerB = new StubLlmProvider("B");
        providerC = new StubLlmProvider("C");

        providers = new HashMap<>();
        providers.put("A", providerA);
        providers.put("B", providerB);
        providers.put("C", providerC);
    }

    @Test
    public void testFirstProviderSuccess() {
        providerA.setOutcome(new LlmResponse("{\"res\":1}", "A", "m-a"), null);
        FailoverLlmClient client = new FailoverLlmClient(properties, providers);

        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));
        assertEquals("A", res.providerId());
        assertEquals(1, providerA.getCallCount());
        assertEquals(0, providerB.getCallCount());
    }

    @Test
    public void testRetryOnRetryableAndSucceed() {
        // First call throws RETRYABLE, but since our stub throws the same exception every time unless we change behavior dynamically, let's make a dynamic stub
        LlmProvider dynamicA = new LlmProvider() {
            int attempts = 0;
            @Override
            public String id() { return "A"; }
            @Override
            public LlmResponse complete(LlmRequest request) {
                attempts++;
                if (attempts == 1) {
                    throw new LlmException(LlmException.Kind.RETRYABLE, "A", 503, null, "503 Unavailable");
                }
                return new LlmResponse("{\"recovered\":true}", "A", "m-a");
            }
        };
        providers.put("A", dynamicA);
        FailoverLlmClient client = new FailoverLlmClient(properties, providers);

        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));
        assertEquals("A", res.providerId());
        assertEquals("{\"recovered\":true}", res.jsonText());
    }

    @Test
    public void testRetryExhaustedThenFailover() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.RETRYABLE, "A", 503, null, "503 Unavailable"));
        providerB.setOutcome(new LlmResponse("{\"res\":\"B\"}", "B", "m-b"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));

        assertEquals("B", res.providerId());
        assertEquals(2, providerA.getCallCount()); // Tried 2 attempts on RETRYABLE
        assertEquals(1, providerB.getCallCount());
    }

    @Test
    public void testImmediateFailoverOnFatalOrBadOutput() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "A", 400, null, "400 Bad Request"));
        providerB.setOutcome(null, new LlmException(LlmException.Kind.BAD_OUTPUT, "B", 200, null, "Invalid JSON"));
        providerC.setOutcome(new LlmResponse("{\"res\":\"C\"}", "C", "m-c"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));

        assertEquals("C", res.providerId());
        assertEquals(1, providerA.getCallCount()); // Only 1 attempt on FATAL
        assertEquals(1, providerB.getCallCount()); // Only 1 attempt on BAD_OUTPUT
        assertEquals(1, providerC.getCallCount());
    }

    @Test
    public void testCircuitBreakerTripsAndSkipsProvider() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "A", 500, null, "Server error"));
        providerB.setOutcome(new LlmResponse("{\"res\":\"B\"}", "B", "m-b"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);

        // Fail 3 times to trip circuit breaker for A
        client.complete(new LlmRequest("test", "prompt", null, 0.0));
        client.complete(new LlmRequest("test", "prompt", null, 0.0));
        client.complete(new LlmRequest("test", "prompt", null, 0.0));

        assertEquals(3, providerA.getCallCount());

        // 4th call should skip A entirely because circuit breaker is open
        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));
        assertEquals("B", res.providerId());
        assertEquals(3, providerA.getCallCount()); // Still 3, was skipped!
    }

    @Test
    public void testIgnoreCircuitBreakerIfAllProvidersOpen() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "A", 500, null, "Err A"));
        providerB.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "B", 500, null, "Err B"));
        providerC.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "C", 500, null, "Err C"));

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);

        // Trip breaker for all three
        for (int i = 0; i < 3; i++) {
            try { client.complete(new LlmRequest("test", "prompt", null, 0.0)); } catch (LlmException ignored) {}
        }

        // Now all three are open. Next call should ignore breaker and still attempt them instead of throwing immediately without calling
        int beforeA = providerA.getCallCount();
        assertThrows(LlmException.class, () -> client.complete(new LlmRequest("test", "prompt", null, 0.0)));
        assertTrue(providerA.getCallCount() > beforeA);
    }

    @Test
    public void testTaskChainOverride() {
        LlmProperties.TaskProperties taskProps = new LlmProperties.TaskProperties();
        taskProps.setChain(List.of("C", "B"));
        properties.getTasks().put("special-task", taskProps);

        providerC.setOutcome(new LlmResponse("{\"res\":\"C\"}", "C", "m-c"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmResponse res = client.complete(new LlmRequest("special-task", "prompt", null, 0.0));

        assertEquals("C", res.providerId());
        assertEquals(0, providerA.getCallCount()); // Main chain start A skipped
        assertEquals(1, providerC.getCallCount());
    }

    @Test
    public void testUnknownProviderInChainThrowsAtStartup() {
        properties.setChain(List.of("A", "UNKNOWN_ID"));
        assertThrows(IllegalStateException.class, () -> new FailoverLlmClient(properties, providers));
    }

    @Test
    public void testBlankApiKeyAndAllowNoKeyFalseIsSkipped() {
        LlmProperties.ProviderProperties propA = new LlmProperties.ProviderProperties();
        propA.setApiKey("   ");
        propA.setAllowNoKey(false);
        properties.getProviders().put("A", propA);

        LlmProperties.ProviderProperties propB = new LlmProperties.ProviderProperties();
        propB.setApiKey("valid-key");
        properties.getProviders().put("B", propB);

        providerB.setOutcome(new LlmResponse("{\"res\":\"B\"}", "B", "m-b"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));

        assertEquals("B", res.providerId());
        assertEquals(0, providerA.getCallCount());
        assertEquals(1, providerB.getCallCount());
    }

    @Test
    public void testChainExhaustedMentionsEachProviderFailure() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "A", 400, null, "Err A"));
        providerB.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "B", 500, null, "Err B"));
        providerC.setOutcome(null, new LlmException(LlmException.Kind.BAD_OUTPUT, "C", 200, null, "Err C"));

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmException ex = assertThrows(LlmException.class, () -> client.complete(new LlmRequest("test", "prompt", null, 0.0)));

        assertTrue(ex.getMessage().contains("A: Err A"), "Message should contain A: Err A, but was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("B: Err B"), "Message should contain B: Err B, but was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("C: Err C"), "Message should contain C: Err C, but was: " + ex.getMessage());
    }
}
