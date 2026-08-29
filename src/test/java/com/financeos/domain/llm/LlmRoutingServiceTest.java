package com.financeos.domain.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.llm.dto.LlmBucketHealthDto;
import com.financeos.api.llm.dto.LlmRoutingGroupDto;
import com.financeos.api.llm.dto.RoutingEntryRequest;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.*;
import com.financeos.llm.provider.OpenAiCompatProvider;
import com.financeos.llm.security.LlmKeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class LlmRoutingServiceTest {

    private LlmProperties properties;
    private LlmTaskPrefRepository taskPrefRepository;
    private LlmKeyRepository keyRepository;
    private UserRepository userRepository;
    private FailoverLlmClient llmClient;
    private LlmRoutingService routingService;
    private BucketStateRegistry bucketStateRegistry;

    private UUID userId;
    private User user;

    @BeforeEach
    public void setUp() {
        properties = new LlmProperties();
        properties.setChain(List.of("openrouter", "gemini"));
        LlmProperties.TaskProperties chatTask = new LlmProperties.TaskProperties();
        chatTask.setChain(List.of("gemini", "openrouter"));
        properties.getTasks().put("data-chat", chatTask);

        // Configure Gemini
        LlmProperties.ProviderProperties geminiProps = new LlmProperties.ProviderProperties();
        geminiProps.setType("gemini");
        geminiProps.setModel("gemini-3.5-flash-lite");
        LlmProperties.ModelEntry g1 = new LlmProperties.ModelEntry();
        g1.setId("gemini-3.7-flash");
        g1.setLabel("Gemini 3.7 Flash");
        g1.setFree(true);
        g1.setTrainsOnData("yes");
        LlmProperties.ModelEntry g2 = new LlmProperties.ModelEntry();
        g2.setId("gemini-3.5-flash-lite");
        g2.setLabel("Gemini 3.5 Flash Lite");
        g2.setFree(true);
        g2.setTrainsOnData("yes");
        geminiProps.getModelCatalog().addAll(List.of(g1, g2));
        properties.getProviders().put("gemini", geminiProps);

        // Configure OpenRouter
        LlmProperties.ProviderProperties openrouterProps = new LlmProperties.ProviderProperties();
        openrouterProps.setType("openai");
        openrouterProps.setModel("z-ai/glm-5.3-flash");
        openrouterProps.setStructuredOutput("json-schema");
        LlmProperties.ModelEntry o1 = new LlmProperties.ModelEntry();
        o1.setId("z-ai/glm-5.3-flash");
        o1.setLabel("GLM-5.3-Flash");
        o1.setStructuredOutput("json-schema");
        o1.setFree(false);
        o1.setTrainsOnData("no");
        LlmProperties.ModelEntry o2 = new LlmProperties.ModelEntry();
        o2.setId("openrouter/free");
        o2.setLabel("OpenRouter Free");
        o2.setStructuredOutput("json-object");
        o2.setFree(true);
        o2.setTrainsOnData("unknown");
        openrouterProps.getModelCatalog().addAll(List.of(o1, o2));
        properties.getProviders().put("openrouter", openrouterProps);

        // The fixed routing menu users pick from
        properties.setRoutingOptions(List.of(
                routingOption("gemini-chain", "Gemini (full chain)", "gemini", null),
                chainOption("gemini-flash-chain", "Gemini (Flash chain)", "gemini",
                        List.of("gemini-3.7-flash", "gemini-3.5-flash-lite")),
                routingOption("openrouter-free", "OpenRouter (free pool)", "openrouter", "openrouter/free"),
                routingOption("openrouter-glm", "OpenRouter (GLM-5.3-Flash)", "openrouter", "z-ai/glm-5.3-flash")
        ));
        properties.getDefaultRouting().put("chat",
                List.of("gemini-flash-chain", "gemini-chain", "openrouter-free", "openrouter-glm"));
        properties.getDefaultRouting().put("default",
                List.of("gemini-chain", "openrouter-free", "openrouter-glm", "gemini-flash-chain"));

        taskPrefRepository = mock(LlmTaskPrefRepository.class);
        keyRepository = mock(LlmKeyRepository.class);
        userRepository = mock(UserRepository.class);
        bucketStateRegistry = new BucketStateRegistry();

        Map<String, LlmProvider> providers = new HashMap<>();
        providers.put("gemini", mock(LlmProvider.class));
        providers.put("openrouter", mock(LlmProvider.class));

        llmClient = new FailoverLlmClient(properties, providers, keyRepository, taskPrefRepository, null, null, bucketStateRegistry);
        routingService = new LlmRoutingService(properties, taskPrefRepository, keyRepository, userRepository, llmClient);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(user));
    }

    private static LlmProperties.RoutingOption chainOption(String id, String label, String provider, List<String> models) {
        LlmProperties.RoutingOption o = routingOption(id, label, provider, null);
        o.setModels(models);
        return o;
    }

    private static LlmProperties.RoutingOption routingOption(String id, String label, String provider, String model) {
        LlmProperties.RoutingOption o = new LlmProperties.RoutingOption();
        o.setId(id);
        o.setLabel(label);
        o.setProvider(provider);
        o.setModel(model);
        return o;
    }

    private LlmKey activeKey(String provider) {
        LlmKey k = new LlmKey();
        k.setId(UUID.randomUUID());
        k.setUser(user);
        k.setProvider(provider);
        k.setKeyLast4("1234");
        k.setPosition(1);
        k.setStatus(LlmKeyStatus.ACTIVE);
        return k;
    }

    @Test
    public void testStartupRejectsBadRoutingConfig() {
        Map<String, LlmProvider> providers = new HashMap<>();
        providers.put("gemini", mock(LlmProvider.class));
        providers.put("openrouter", mock(LlmProvider.class));

        LlmProperties bad = new LlmProperties();
        bad.getProviders().putAll(properties.getProviders());
        bad.setRoutingOptions(List.of(routingOption("ghost", "Ghost", "no-such-provider", null)));
        assertThrows(IllegalStateException.class,
                () -> new FailoverLlmClient(bad, providers, null, null, null, null, bucketStateRegistry),
                "an option naming an unconfigured provider must fail the boot");

        LlmProperties typo = new LlmProperties();
        typo.getProviders().putAll(properties.getProviders());
        typo.setRoutingOptions(properties.getRoutingOptions());
        typo.getDefaultRouting().put("chat", List.of("gemini-chian"));
        assertThrows(IllegalStateException.class,
                () -> new FailoverLlmClient(typo, providers, null, null, null, null, bucketStateRegistry),
                "a typo'd option id in default-routing must fail the boot, not silently reroute");

        LlmProperties both = new LlmProperties();
        both.getProviders().putAll(properties.getProviders());
        LlmProperties.RoutingOption conflicted = routingOption("conflicted", "Conflicted", "gemini", "gemini-3.7-flash");
        conflicted.setModels(List.of("gemini-3.5-flash"));
        both.setRoutingOptions(List.of(conflicted));
        assertThrows(IllegalStateException.class,
                () -> new FailoverLlmClient(both, providers, null, null, null, null, bucketStateRegistry),
                "an option cannot be both a chain and a pinned model");
    }

    @Test
    public void testAllOptionsAlwaysListedAndNewOnesAppendRatherThanPromote() {
        // A saved order that predates a later config addition.
        LlmTaskPref saved1 = new LlmTaskPref();
        saved1.setOptionId("openrouter-glm");
        saved1.setPosition(1);
        LlmTaskPref saved2 = new LlmTaskPref();
        saved2.setOptionId("gemini-chain");
        saved2.setPosition(2);
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(saved1, saved2));

        LlmRoutingGroupDto shown = routingService.getGroupRouting(userId, LlmTaskGroup.CHAT);

        // Every configured option appears, never a subset.
        assertEquals(properties.getRoutingOptions().size(), shown.entries().size());
        // The user's explicit choices keep the top, in their order.
        assertEquals("openrouter-glm", shown.entries().get(0).optionId());
        assertEquals("gemini-chain", shown.entries().get(1).optionId());
        // Options they never ordered land after them rather than jumping the queue.
        assertEquals(Set.of("gemini-flash-chain", "openrouter-free"),
                shown.entries().subList(2, 4).stream()
                        .map(com.financeos.api.llm.dto.RoutingEntryDto::optionId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(shown.usingDefaults());
        // Positions stay dense and 1-based.
        assertEquals(List.of(1, 2, 3, 4),
                shown.entries().stream().map(com.financeos.api.llm.dto.RoutingEntryDto::position).toList());
    }

    @Test
    public void testOverlappingChainOptionsDoNotQueueTheSameBucketTwice() {
        LlmKey key = activeKey("gemini");
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(key));

        // Flash chain then full chain: gemini-3.5-flash-lite is reachable from both.
        List<FailoverLlmClient.ChainEntry> chain = new ArrayList<>();
        chain.addAll(List.of(
                new FailoverLlmClient.ChainEntry("gemini", "gemini-3.7-flash"),
                new FailoverLlmClient.ChainEntry("gemini", "gemini-3.5-flash-lite"),
                new FailoverLlmClient.ChainEntry("gemini", "gemini-3.5-flash-lite")));

        List<FailoverLlmClient.BucketTarget> buckets = llmClient.buildBucketListForTest(userId, chain);
        assertEquals(2, buckets.size(), "a repeated (key, model) must not be attempted twice");
        assertEquals(List.of("gemini-3.7-flash", "gemini-3.5-flash-lite"),
                buckets.stream().map(FailoverLlmClient.BucketTarget::model).toList());
    }

    @Test
    public void testLlmTasksGroupOfMapping() {
        assertEquals(LlmTaskGroup.CHAT, LlmTasks.groupOf("data-chat"));
        assertEquals(LlmTaskGroup.DEFAULT, LlmTasks.groupOf("email-classify"));
        assertEquals(LlmTaskGroup.DEFAULT, LlmTasks.groupOf("email-extract"));
        assertEquals(LlmTaskGroup.DEFAULT, LlmTasks.groupOf("categorize"));
        assertEquals(LlmTaskGroup.DEFAULT, LlmTasks.groupOf("unknown-future-task"));
        assertEquals(LlmTaskGroup.DEFAULT, LlmTasks.groupOf(null));
    }

    @Test
    public void testResolveChainUnchangedWhenNoUserPrefs() {
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), any())).thenReturn(Collections.emptyList());

        // default group: full Gemini chain first (provider model list), then the OpenRouter pair,
        // then the Flash chain in reserve.
        List<FailoverLlmClient.ChainEntry> defaultChain = llmClient.resolveChain(userId, "categorize");
        assertEquals("gemini", defaultChain.get(0).providerId());
        assertEquals("openrouter/free",
                defaultChain.stream().filter(e -> "openrouter".equals(e.providerId())).findFirst().orElseThrow().model());

        // chat leads with the Flash chain, expanded model-major in the configured order
        List<FailoverLlmClient.ChainEntry> chatChain = llmClient.resolveChain(userId, "data-chat");
        assertEquals("gemini", chatChain.get(0).providerId());
        assertEquals("gemini-3.7-flash", chatChain.get(0).model());
        assertEquals("gemini-3.5-flash-lite", chatChain.get(1).model());
    }

    @Test
    public void testResolveChainReturnsUserPrefsInPositionOrderForMatchingGroup() {
        LlmTaskPref p1 = new LlmTaskPref();
        p1.setOptionId("openrouter-glm");
        p1.setPosition(1);

        LlmTaskPref p2 = new LlmTaskPref();
        p2.setOptionId("gemini-flash-chain");
        p2.setPosition(2);

        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(p1, p2));

        List<FailoverLlmClient.ChainEntry> chatChain = llmClient.resolveChain(userId, "data-chat");
        // openrouter-glm is one pinned model; gemini-flash-chain expands to its two models.
        assertEquals(3, chatChain.size());
        assertEquals("openrouter", chatChain.get(0).providerId());
        assertEquals("z-ai/glm-5.3-flash", chatChain.get(0).model());
        assertEquals("gemini", chatChain.get(1).providerId());
        assertEquals("gemini-3.7-flash", chatChain.get(1).model());
    }

    @Test
    public void testUserWithPrefsOnlyForChatStillGetsConfigChainForCategorize() {
        LlmTaskPref p1 = new LlmTaskPref();
        p1.setOptionId("openrouter-glm");
        p1.setPosition(1);

        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(p1));
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(Collections.emptyList());

        // The background group has no saved order, so it uses the shipped default:
        // full Gemini chain first, Flash chain held in reserve at the end.
        List<FailoverLlmClient.ChainEntry> catChain = llmClient.resolveChain(userId, "categorize");
        assertEquals("gemini", catChain.get(0).providerId());
        assertEquals("gemini-3.5-flash-lite", catChain.get(catChain.size() - 1).model());
    }

    @Test
    public void testChainOptionExpandsToExactlyItsConfiguredModels() {
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        LlmKeyEncryptionService encryptionService = new LlmKeyEncryptionService(masterKey);

        LlmKey k1 = new LlmKey();
        k1.setId(UUID.randomUUID());
        k1.setUser(user);
        k1.setProvider("gemini");
        k1.setKeyCiphertext(encryptionService.encrypt("k1"));
        k1.setPosition(1);
        k1.setStatus(LlmKeyStatus.ACTIVE);

        LlmKey k2 = new LlmKey();
        k2.setId(UUID.randomUUID());
        k2.setUser(user);
        k2.setProvider("gemini");
        k2.setKeyCiphertext(encryptionService.encrypt("k2"));
        k2.setPosition(2);
        k2.setStatus(LlmKeyStatus.ACTIVE);

        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(k1, k2));

        LlmTaskPref pref = new LlmTaskPref();
        pref.setOptionId("gemini-flash-chain");
        pref.setPosition(1);
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(pref));

        // The Flash chain must expand to its OWN models in order — never fan out over the
        // provider's whole list, which is what the full-chain option is for.
        List<FailoverLlmClient.ChainEntry> chain = llmClient.resolveChain(userId, "data-chat");
        assertEquals(List.of("gemini-3.7-flash", "gemini-3.5-flash-lite"),
                chain.stream().map(FailoverLlmClient.ChainEntry::model).toList());

        // Model-major across both keys: model 1 on every key, then model 2 on every key.
        List<FailoverLlmClient.BucketTarget> buckets = llmClient.buildBucketListForTest(userId, chain);
        assertEquals(List.of("gemini-3.7-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite", "gemini-3.5-flash-lite"),
                buckets.stream().map(FailoverLlmClient.BucketTarget::model).toList());
    }

    @Test
    public void testReordering3RowGroupViaPutDoesNotViolateUniqueConstraint() {
        List<RoutingEntryRequest> newEntries = List.of(
                new RoutingEntryRequest("openrouter-glm"),
                new RoutingEntryRequest("gemini-chain"),
                new RoutingEntryRequest("gemini-flash-chain"),
                new RoutingEntryRequest("openrouter-free")
        );

        LlmRoutingGroupDto updated = routingService.updateRouting(userId, LlmTaskGroup.CHAT, newEntries);

        verify(taskPrefRepository, times(1)).deleteByUserIdAndTaskGroup(eq(userId), eq(LlmTaskGroup.CHAT));
        verify(taskPrefRepository, times(1)).flush();
        verify(taskPrefRepository, times(1)).saveAll(any());
        assertNotNull(updated);
    }

    @Test
    public void testUpdateRoutingRejectsUnknownOptionAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> routingService.updateRouting(
                userId, LlmTaskGroup.CHAT, List.of(new RoutingEntryRequest("not-a-real-option"))));

        assertThrows(IllegalArgumentException.class, () -> routingService.updateRouting(
                userId, LlmTaskGroup.CHAT,
                List.of(new RoutingEntryRequest("openrouter-glm"), new RoutingEntryRequest("openrouter-glm"))));

        assertThrows(IllegalArgumentException.class, () -> routingService.updateRouting(
                userId, LlmTaskGroup.CHAT, List.of()));

        // A partial order would leave an option unreachable — rejected rather than silently padded.
        assertThrows(IllegalArgumentException.class, () -> routingService.updateRouting(
                userId, LlmTaskGroup.CHAT,
                List.of(new RoutingEntryRequest("openrouter-glm"), new RoutingEntryRequest("gemini-chain"))));
    }

    @Test
    public void testRoutingOptionsMarkUnavailableWhenNoKeyForProvider() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(activeKey("openrouter")));

        Map<String, Boolean> availability = new HashMap<>();
        routingService.getRoutingOptions(userId).forEach(o -> availability.put(o.id(), o.available()));

        assertEquals(4, availability.size());
        assertTrue(availability.get("openrouter-glm"));
        assertTrue(availability.get("openrouter-free"));
        assertFalse(availability.get("gemini-chain"));
        assertFalse(availability.get("gemini-flash-chain"));
    }

    @Test
    public void testChatDoesNotInheritTheDefaultGroupsPreferences() {
        LlmTaskPref bulk = new LlmTaskPref();
        bulk.setOptionId("openrouter-glm");
        bulk.setPosition(1);

        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(bulk));
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(Collections.emptyList());

        // Chat falls through to the shipped default order, which leads with the Flash chain —
        // NOT the user's slow bulk model.
        List<FailoverLlmClient.ChainEntry> chatChain = llmClient.resolveChain(userId, "data-chat");
        assertEquals("gemini", chatChain.get(0).providerId());
        assertEquals("gemini-3.7-flash", chatChain.get(0).model());
        assertNotEquals("openrouter", chatChain.get(0).providerId());

        // ...and the settings screen must report exactly that, or it would misdescribe what runs.
        LlmRoutingGroupDto shown = routingService.getGroupRouting(userId, LlmTaskGroup.CHAT);
        assertTrue(shown.usingDefaults());
        assertEquals("gemini-flash-chain", shown.entries().get(0).optionId());
    }

    @Test
    public void testHealthUsesTheSameBucketKeysAsTheFailoverLoop() {
        LlmKey key = activeKey("openrouter");
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(key));

        // Cool down the bucket a *customised* user would actually hit (provider + pinned model).
        String bucketKey = FailoverLlmClient.bucketKeyFor(key.getId(), "z-ai/glm-5.3-flash");
        bucketStateRegistry.recordFailure(bucketKey, "openrouter", null);
        bucketStateRegistry.recordFailure(bucketKey, "openrouter", null);
        bucketStateRegistry.recordFailure(bucketKey, "openrouter", null);

        LlmBucketHealthDto glm = routingService.getHealth(userId).stream()
                .filter(h -> "z-ai/glm-5.3-flash".equals(h.model()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GLM bucket missing from health"));

        assertTrue(glm.inCooldown(), "health must observe the cooldown the failover loop recorded");
    }

    @Test
    public void testModelLevelStructuredOutputOverridesProviderLevel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("name").put("type", "string");

        LlmRequest request = new LlmRequest("test", "Extract", schema, 0.0);

        LlmProperties.ProviderProperties props = properties.getProviders().get("openrouter");
        // Model with json-schema
        String bodySchema = OpenAiCompatProvider.buildRequestBody(request, props, "z-ai/glm-5.3-flash", mapper);
        assertTrue(bodySchema.contains("\"type\":\"json_schema\""));

        // Model with json-object
        String bodyObj = OpenAiCompatProvider.buildRequestBody(request, props, "openrouter/free", mapper);
        assertTrue(bodyObj.contains("\"type\":\"json_object\""));
    }

    @Test
    public void testHealthEndpointReturnsCooldownStatusWithoutLeakingSecrets() {
        UUID keyId = UUID.randomUUID();
        LlmKey k1 = new LlmKey();
        k1.setId(keyId);
        k1.setUser(user);
        k1.setProvider("openrouter");
        k1.setKeyLast4("9876");
        k1.setLabel("My Key");
        k1.setKeyCiphertext("super-secret-ciphertext");
        k1.setStatus(LlmKeyStatus.ACTIVE);

        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(k1));

        // Recorded under the real bucket name — key id AND model. A cooldown filed against the bare
        // key id (as an earlier revision did) belongs to no bucket the failover loop ever creates.
        bucketStateRegistry.handle429(
                FailoverLlmClient.bucketKeyFor(keyId, "z-ai/glm-5.3-flash"),
                "openrouter", "z-ai/glm-5.3-flash", "Rate limit", 60L, null);

        List<LlmBucketHealthDto> health = routingService.getHealth(userId);

        // One row per model this provider can actually be invoked with — both OpenRouter options.
        assertEquals(2, health.size());
        assertEquals(Set.of("z-ai/glm-5.3-flash", "openrouter/free"),
                health.stream().map(LlmBucketHealthDto::model).collect(java.util.stream.Collectors.toSet()));

        LlmBucketHealthDto glm = health.stream()
                .filter(h -> "z-ai/glm-5.3-flash".equals(h.model()))
                .findFirst().orElseThrow();
        assertEquals("openrouter", glm.provider());
        assertEquals("9876", glm.keyLast4());
        assertEquals("My Key", glm.keyLabel());
        assertTrue(glm.inCooldown());
        assertNotNull(glm.cooldownUntil());

        LlmBucketHealthDto free = health.stream()
                .filter(h -> "openrouter/free".equals(h.model()))
                .findFirst().orElseThrow();
        assertFalse(free.inCooldown(), "a sibling model must not inherit another model's cooldown");

        // Nothing in the payload exposes key material.
        assertFalse(health.toString().contains("super-secret-ciphertext"));
    }
}
