package com.financeos.domain.llm;

import com.financeos.api.llm.dto.LlmBucketHealthDto;
import com.financeos.api.llm.dto.LlmRoutingGroupDto;
import com.financeos.api.llm.dto.RoutingEntryDto;
import com.financeos.api.llm.dto.RoutingEntryRequest;
import com.financeos.api.llm.dto.TestModelRequest;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.BucketStateRegistry;
import com.financeos.llm.FailoverLlmClient;
import com.financeos.llm.LlmCallOptions;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.LlmProvider;
import com.financeos.llm.LlmTaskGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * User-typed models on routing rows: how they read back, how they save, and how they reach the
 * chain and the health view. The fixed-menu behaviour is covered by {@link LlmRoutingServiceTest}.
 */
public class LlmRoutingServiceCustomModelsTest {

    private LlmProperties properties;
    private LlmTaskPrefRepository taskPrefRepository;
    private LlmKeyRepository keyRepository;
    private UserRepository userRepository;
    private FailoverLlmClient llmClient;
    private LlmRoutingService routingService;
    private UUID userId;
    private User user;

    @BeforeEach
    public void setUp() {
        properties = new LlmProperties();

        LlmProperties.ProviderProperties gemini = new LlmProperties.ProviderProperties();
        gemini.setType("gemini");
        gemini.setLabel("Google Gemini");
        gemini.setModel("gemini-3.5-flash-lite");
        gemini.setModels(List.of("gemini-3.8-flash", "gemini-3.5-flash-lite"));
        properties.getProviders().put("gemini", gemini);

        LlmProperties.ProviderProperties openrouter = new LlmProperties.ProviderProperties();
        openrouter.setType("openai");
        openrouter.setLabel("OpenRouter");
        openrouter.setModel("z-ai/glm-5.3-flash");
        openrouter.setStructuredOutput("json-object");
        properties.getProviders().put("openrouter", openrouter);

        LlmProperties.RoutingOption geminiChain = new LlmProperties.RoutingOption();
        geminiChain.setId("gemini-chain");
        geminiChain.setLabel("Gemini (full chain)");
        geminiChain.setProvider("gemini");
        LlmProperties.RoutingOption glm = new LlmProperties.RoutingOption();
        glm.setId("openrouter-glm");
        glm.setLabel("OpenRouter (GLM-5.3-Flash)");
        glm.setProvider("openrouter");
        glm.setModel("z-ai/glm-5.3-flash");
        properties.setRoutingOptions(List.of(geminiChain, glm));
        properties.getDefaultRouting().put("chat", List.of("gemini-chain", "openrouter-glm"));
        properties.getDefaultRouting().put("default", List.of("openrouter-glm", "gemini-chain"));

        taskPrefRepository = mock(LlmTaskPrefRepository.class);
        keyRepository = mock(LlmKeyRepository.class);
        userRepository = mock(UserRepository.class);
        Map<String, LlmProvider> providers = new HashMap<>();
        providers.put("gemini", mock(LlmProvider.class));
        providers.put("openrouter", mock(LlmProvider.class));
        llmClient = new FailoverLlmClient(properties, providers, keyRepository, taskPrefRepository, null, null, new BucketStateRegistry());
        routingService = new LlmRoutingService(properties, taskPrefRepository, keyRepository, userRepository, llmClient);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(user));
    }

    private static LlmTaskPref menuPref(String optionId, int position) {
        LlmTaskPref p = new LlmTaskPref();
        p.setOptionId(optionId);
        p.setPosition(position);
        return p;
    }

    private static LlmTaskPref customPref(String provider, String model, int position) {
        LlmTaskPref p = new LlmTaskPref();
        p.setOptionId("custom");
        p.setProvider(provider);
        p.setModel(model);
        p.setPosition(position);
        p.setStructuredOutput("json-schema");
        p.setReasoningEffort("low");
        p.setMaxOutputTokens(4096);
        p.setProviderPin("anthropic");
        return p;
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

    private static RoutingEntryRequest customEntry(String provider, String model) {
        return new RoutingEntryRequest("custom", provider, model, "JSON-OBJECT", "LOW", 2048, "openai");
    }

    // ---------------------------------------------------------------- read

    @Test
    public void customRowReadsBackAtItsSavedPositionWithItsKnobsAndKeyState() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(activeKey("openrouter")));
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("openrouter", "anthropic/claude-haiku-5.5", 1), menuPref("openrouter-glm", 2)));

        LlmRoutingGroupDto dto = routingService.getGroupRouting(userId, LlmTaskGroup.DEFAULT);

        assertFalse(dto.usingDefaults());
        assertEquals(List.of("custom", "openrouter-glm", "gemini-chain"),
                dto.entries().stream().map(RoutingEntryDto::optionId).toList(),
                "custom row first, saved menu option second, unsaved menu option appended last");
        RoutingEntryDto custom = dto.entries().get(0);
        assertTrue(custom.custom());
        assertEquals(1, custom.position());
        assertEquals("anthropic/claude-haiku-5.5", custom.model());
        assertEquals("anthropic/claude-haiku-5.5", custom.optionLabel());
        assertEquals("openrouter", custom.provider());
        assertEquals("OpenRouter", custom.providerName());
        assertTrue(custom.hasKey());
        assertEquals("json-schema", custom.structuredOutput());
        assertEquals("low", custom.reasoningEffort());
        assertEquals(4096, custom.maxOutputTokens());
        assertEquals("anthropic", custom.providerPin());
        assertFalse(dto.entries().get(1).custom());
        assertNull(dto.entries().get(1).reasoningEffort());
    }

    @Test
    public void customRowWithoutAKeyIsListedButNotReady() {
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(menuPref("gemini-chain", 1), customPref("gemini", "gemini-3.8-flash", 2), menuPref("openrouter-glm", 3)));

        LlmRoutingGroupDto dto = routingService.getGroupRouting(userId, LlmTaskGroup.CHAT);

        assertEquals(List.of("gemini-chain", "custom", "openrouter-glm"),
                dto.entries().stream().map(RoutingEntryDto::optionId).toList());
        assertFalse(dto.entries().get(1).hasKey());
    }

    @Test
    public void customRowMissingItsModelIsDroppedFromTheReadBack() {
        LlmTaskPref broken = customPref("gemini", null, 1);
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.CHAT)))
                .thenReturn(List.of(broken, menuPref("openrouter-glm", 2)));

        LlmRoutingGroupDto dto = routingService.getGroupRouting(userId, LlmTaskGroup.CHAT);

        assertEquals(List.of("openrouter-glm", "gemini-chain"),
                dto.entries().stream().map(RoutingEntryDto::optionId).toList());
    }

    // ---------------------------------------------------------------- write

    @Test
    public void updateRoutingSavesCustomRowsWithNormalisedKnobsAlongsideEveryMenuOption() {
        routingService.updateRouting(userId, LlmTaskGroup.DEFAULT, List.of(
                customEntry("OpenRouter", " openai/gpt-5-mini "),
                new RoutingEntryRequest("gemini-chain"),
                new RoutingEntryRequest("openrouter-glm")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmTaskPref>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskPrefRepository).saveAll(captor.capture());
        List<LlmTaskPref> saved = captor.getValue();

        assertEquals(3, saved.size());
        LlmTaskPref custom = saved.get(0);
        assertEquals("custom", custom.getOptionId());
        assertEquals(1, custom.getPosition());
        assertEquals("openrouter", custom.getProvider());
        assertEquals("openai/gpt-5-mini", custom.getModel());
        assertEquals("json-object", custom.getStructuredOutput());
        assertEquals("low", custom.getReasoningEffort());
        assertEquals(2048, custom.getMaxOutputTokens());
        assertEquals("openai", custom.getProviderPin());
        assertSame(user, custom.getUser());
        assertEquals(LlmTaskGroup.DEFAULT, custom.getTaskGroup());

        assertEquals("gemini-chain", saved.get(1).getOptionId());
        assertEquals(2, saved.get(1).getPosition());
        assertNull(saved.get(1).getProvider());
        assertNull(saved.get(1).getModel());
        assertEquals("openrouter-glm", saved.get(2).getOptionId());
    }

    @Test
    public void updateRoutingStillRequiresEveryMenuOptionWhenCustomRowsArePresent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                routingService.updateRouting(userId, LlmTaskGroup.DEFAULT, List.of(
                        customEntry("openrouter", "openai/gpt-5-mini"),
                        new RoutingEntryRequest("gemini-chain"))));
        assertTrue(ex.getMessage().contains("Missing: openrouter-glm"), ex.getMessage());
        verify(taskPrefRepository, never()).saveAll(any());
    }

    @Test
    public void updateRoutingRejectsTheSameCustomModelTwiceRegardlessOfCase() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                routingService.updateRouting(userId, LlmTaskGroup.DEFAULT, List.of(
                        customEntry("openrouter", "Foo/Bar"),
                        customEntry("OPENROUTER", "foo/bar"),
                        new RoutingEntryRequest("gemini-chain"),
                        new RoutingEntryRequest("openrouter-glm"))));
        assertTrue(ex.getMessage().contains("Duplicate custom model"), ex.getMessage());
    }

    @Test
    public void updateRoutingRejectsACustomRowForAProviderThisDeploymentDoesNotHave() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                routingService.updateRouting(userId, LlmTaskGroup.DEFAULT, List.of(
                        customEntry("anthropic", "claude-haiku-5.5"),
                        new RoutingEntryRequest("gemini-chain"),
                        new RoutingEntryRequest("openrouter-glm"))));
        assertTrue(ex.getMessage().contains("Unknown provider"), ex.getMessage());
    }

    @Test
    public void updateRoutingRejectsACustomRowWithAnInvalidKnob() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                routingService.updateRouting(userId, LlmTaskGroup.DEFAULT, List.of(
                        new RoutingEntryRequest("custom", "openrouter", "foo/bar", null, "turbo", null, null),
                        new RoutingEntryRequest("gemini-chain"),
                        new RoutingEntryRequest("openrouter-glm"))));
        assertTrue(ex.getMessage().contains("Reasoning effort"), ex.getMessage());
    }

    @Test
    public void customModelSpecFromTheProbeRequestIsNormalisedTheSameWayAsASave() {
        CustomModelSpec spec = routingService.customModelSpec(
                new TestModelRequest("OpenRouter", " foo/bar ", "JSON-OBJECT", "Low", 10, " z-ai "));
        assertEquals("openrouter", spec.provider());
        assertEquals("foo/bar", spec.model());
        assertEquals(new LlmCallOptions("json-object", "low", 10, "z-ai"), spec.options());
    }

    // ---------------------------------------------------------------- chain + health

    @Test
    public void customRowsReachTheChainAsOneEntryCarryingTheirKnobs() {
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("OpenRouter", "anthropic/claude-haiku-5.5", 1), menuPref("gemini-chain", 2)));

        List<FailoverLlmClient.ChainEntry> chain = llmClient.resolveChain(userId, "categorize");

        assertEquals("openrouter", chain.get(0).providerId());
        assertEquals("anthropic/claude-haiku-5.5", chain.get(0).model());
        assertEquals(new LlmCallOptions("json-schema", "low", 4096, "anthropic"), chain.get(0).options());
        // gemini-chain expands to the provider's two models, each with no knobs.
        assertEquals(3, chain.size());
        assertEquals("gemini-3.8-flash", chain.get(1).model());
        assertEquals(LlmCallOptions.NONE, chain.get(1).options());
    }

    @Test
    public void customRowWithoutAModelIsSkippedInTheChain() {
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("openrouter", " ", 1), menuPref("openrouter-glm", 2)));

        List<FailoverLlmClient.ChainEntry> chain = llmClient.resolveChain(userId, "categorize");

        assertEquals(1, chain.size());
        assertEquals("z-ai/glm-5.3-flash", chain.get(0).model());
    }

    @Test
    public void healthListsACustomModelUnderTheKeyThatWouldServeIt() {
        LlmKey key = activeKey("openrouter");
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(key));
        when(taskPrefRepository.findByUserIdOrderByTaskGroupAscPositionAsc(eq(userId)))
                .thenReturn(List.of(customPref("openrouter", "foo/bar", 1), customPref("gemini", "gemini-3.8-flash", 2)));

        List<LlmBucketHealthDto> health = routingService.getHealth(userId);

        Set<String> models = new HashSet<>();
        for (LlmBucketHealthDto h : health) {
            assertEquals("openrouter", h.provider(), "no Gemini key, so no Gemini rows");
            models.add(h.model());
        }
        assertTrue(models.contains("foo/bar"), models.toString());
        assertTrue(models.contains("z-ai/glm-5.3-flash"), models.toString());
        assertEquals("foo/bar", health.stream().filter(h -> "foo/bar".equals(h.model())).findFirst().orElseThrow().modelLabel(),
                "a typed model has no catalog label, so the id is shown");
    }
}
