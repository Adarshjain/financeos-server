package com.financeos.llm;

import com.financeos.domain.llm.LlmKey;
import com.financeos.domain.llm.LlmKeyRepository;
import com.financeos.domain.llm.LlmKeyStatus;
import com.financeos.domain.user.User;
import com.financeos.llm.security.LlmKeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
        public LlmResponse complete(LlmRequest request, String apiKey, String model) {
            callCount++;
            if (nextException != null) {
                throw nextException;
            }
            if (nextResponse != null) {
                return nextResponse;
            }
            return new LlmResponse("{}", id, model != null ? model : "mock-model");
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
        LlmProvider dynamicA = new LlmProvider() {
            int attempts = 0;
            @Override
            public String id() { return "A"; }
            @Override
            public LlmResponse complete(LlmRequest request, String apiKey, String model) {
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
    public void test429AdvancesBucketWithoutSameBucketRetry() {
        providerA.setOutcome(null, new LlmException(LlmException.Kind.RETRYABLE, "A", 429, 60L, "429 Too Many Requests"));
        providerB.setOutcome(new LlmResponse("{\"res\":\"B\"}", "B", "m-b"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers);
        LlmResponse res = client.complete(new LlmRequest("test", "prompt", null, 0.0));

        assertEquals("B", res.providerId());
        assertEquals(1, providerA.getCallCount());
        assertEquals(1, providerB.getCallCount());
    }

    @Test
    public void testNoKeysThrowsNoKeysException() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);
        LlmKeyRepository mockRepo = Mockito.mock(LlmKeyRepository.class);

        UUID userId = UUID.randomUUID();
        when(mockRepo.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(Collections.emptyList());

        FailoverLlmClient client = new FailoverLlmClient(properties, providers, mockRepo, encryptionService, null);

        LlmException ex = assertThrows(LlmException.class, () -> client.complete(new LlmRequest(userId, "test", "prompt", null, 0.0)));
        assertEquals(LlmException.Kind.NO_KEYS, ex.getKind());
    }

    @Test
    public void testGeminiModelMajorFailover() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);
        LlmKeyRepository mockRepo = Mockito.mock(LlmKeyRepository.class);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        LlmKey key1 = new LlmKey();
        key1.setId(UUID.randomUUID());
        key1.setUser(user);
        key1.setProvider("gemini");
        key1.setKeyCiphertext(encryptionService.encrypt("key-1"));
        key1.setPosition(1);
        key1.setStatus(LlmKeyStatus.ACTIVE);

        LlmKey key2 = new LlmKey();
        key2.setId(UUID.randomUUID());
        key2.setUser(user);
        key2.setProvider("gemini");
        key2.setKeyCiphertext(encryptionService.encrypt("key-2"));
        key2.setPosition(2);
        key2.setStatus(LlmKeyStatus.ACTIVE);

        when(mockRepo.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(List.of(key1, key2));

        LlmProperties props = new LlmProperties();
        props.setChain(List.of("gemini"));
        LlmProperties.ProviderProperties geminiProps = new LlmProperties.ProviderProperties();
        geminiProps.setType("gemini");
        geminiProps.setModels(List.of("gemini-3.7-flash", "gemini-3.6-flash"));
        props.getProviders().put("gemini", geminiProps);

        StubLlmProvider geminiProvider = new StubLlmProvider("gemini");
        Map<String, LlmProvider> providerMap = Map.of("gemini", geminiProvider);

        FailoverLlmClient client = new FailoverLlmClient(props, providerMap, mockRepo, encryptionService, null);
        LlmResponse res = client.complete(new LlmRequest(userId, "test", "prompt", null, 0.0));

        assertNotNull(res);
        assertEquals("gemini", res.providerId());
        assertEquals("gemini-3.7-flash", res.model());
    }

    @Test
    public void test401MarksKeyInvalidAndAdvancesChain() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);
        LlmKeyRepository mockRepo = Mockito.mock(LlmKeyRepository.class);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        LlmKey key1 = new LlmKey();
        key1.setId(UUID.randomUUID());
        key1.setUser(user);
        key1.setProvider("A");
        key1.setKeyCiphertext(encryptionService.encrypt("bad-key"));
        key1.setPosition(1);
        key1.setStatus(LlmKeyStatus.ACTIVE);

        LlmKey key2 = new LlmKey();
        key2.setId(UUID.randomUUID());
        key2.setUser(user);
        key2.setProvider("B");
        key2.setKeyCiphertext(encryptionService.encrypt("good-key"));
        key2.setPosition(2);
        key2.setStatus(LlmKeyStatus.ACTIVE);

        when(mockRepo.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(List.of(key1, key2));

        providerA.setOutcome(null, new LlmException(LlmException.Kind.FATAL, "A", 401, null, "Unauthorized"));
        providerB.setOutcome(new LlmResponse("{\"ok\":true}", "B", "m-b"), null);

        FailoverLlmClient client = new FailoverLlmClient(properties, providers, mockRepo, encryptionService, null);
        LlmResponse res = client.complete(new LlmRequest(userId, "test", "prompt", null, 0.0));

        assertEquals("B", res.providerId());
        assertEquals(LlmKeyStatus.INVALID, key1.getStatus());
        verify(mockRepo, times(1)).save(key1);
    }

    @Test
    public void testRecommendedBatchSizeReturnsFirstUncooledBucket() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);
        LlmKeyRepository mockRepo = Mockito.mock(LlmKeyRepository.class);
        BucketStateRegistry registry = new BucketStateRegistry();

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        LlmKey geminiKey = new LlmKey();
        geminiKey.setId(UUID.randomUUID());
        geminiKey.setUser(user);
        geminiKey.setProvider("gemini");
        geminiKey.setPosition(1);
        geminiKey.setStatus(LlmKeyStatus.ACTIVE);

        when(mockRepo.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(List.of(geminiKey));

        LlmProperties props = new LlmProperties();
        props.setChain(List.of("gemini"));
        LlmProperties.ProviderProperties geminiProps = new LlmProperties.ProviderProperties();
        geminiProps.setBatchSize(200);
        geminiProps.setModels(List.of("gemini-3.7-flash"));
        props.getProviders().put("gemini", geminiProps);

        StubLlmProvider geminiProvider = new StubLlmProvider("gemini");
        Map<String, LlmProvider> providerMap = Map.of("gemini", geminiProvider);

        FailoverLlmClient client = new FailoverLlmClient(props, providerMap, mockRepo, encryptionService, null, registry);

        int batchSize = client.recommendedBatchSize(userId, "categorize");
        assertEquals(200, batchSize);

        // Put bucket in cooldown
        registry.handle429(geminiKey.getId() + ":gemini-3.7-flash", "gemini", "gemini-3.7-flash", "429 Rate Limit", 60L, null);

        int batchSizeAfterCooldown = client.recommendedBatchSize(userId, "categorize");
        assertEquals(50, batchSizeAfterCooldown); // Falls back to 50 when cooled
    }

    @Test
    public void test429BodyWithQuotaIdBeyond200CharsSetsMidnightPtCooldown() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);
        LlmKeyRepository mockRepo = Mockito.mock(LlmKeyRepository.class);

        Instant fixedInstant = Instant.parse("2026-08-25T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        BucketStateRegistry registry = new BucketStateRegistry(fixedClock);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        LlmKey key1 = new LlmKey();
        key1.setId(UUID.randomUUID());
        key1.setUser(user);
        key1.setProvider("A");
        key1.setKeyCiphertext(encryptionService.encrypt("key-1"));
        key1.setPosition(1);
        key1.setStatus(LlmKeyStatus.ACTIVE);

        when(mockRepo.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(List.of(key1));

        String padding = "x".repeat(300);
        String deepQuotaBody = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded " + padding + "\",\"details\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\",\"reason\":\"PerDay\"}]}}";

        providerA.setOutcome(null, new LlmException(LlmException.Kind.RETRYABLE, "A", 429, null, "HTTP 429: Rate limit", deepQuotaBody));

        FailoverLlmClient client = new FailoverLlmClient(properties, providers, mockRepo, encryptionService, null, registry);

        assertThrows(LlmException.class, () -> client.complete(new LlmRequest(userId, "test", "prompt", null, 0.0)));

        // Bucket names always include the model — go through the shared helper rather than
        // rebuilding the key here, so this cannot drift from the failover loop again.
        String bucketKey = FailoverLlmClient.bucketKeyFor(key1.getId(), client.getModelForTest("A"));
        Instant expiry = registry.getSoonestCooldownExpiry(List.of(bucketKey));
        assertNotNull(expiry);

        ZonedDateTime expiryPt = ZonedDateTime.ofInstant(expiry, ZoneId.of("America/Los_Angeles"));
        assertEquals(0, expiryPt.getHour());
        assertEquals(0, expiryPt.getMinute());
        assertEquals(26, expiryPt.getDayOfMonth());
    }
}
