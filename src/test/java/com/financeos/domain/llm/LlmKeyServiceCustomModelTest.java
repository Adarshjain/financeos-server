package com.financeos.domain.llm;

import com.financeos.api.llm.dto.TestKeyResponse;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmCallOptions;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.LlmProvider;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import com.financeos.llm.security.LlmKeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Probing a user-typed model with the user's own key before it may be saved onto a routing row. */
public class LlmKeyServiceCustomModelTest {

    private static class RecordingProvider implements LlmProvider {
        String lastKey;
        String lastModel;
        LlmCallOptions lastOptions;
        LlmRequest lastRequest;
        LlmException failWith;

        @Override
        public String id() {
            return "openrouter";
        }

        @Override
        public LlmResponse complete(LlmRequest request, String apiKey, String model) {
            throw new AssertionError("the probe must use the options-aware overload");
        }

        @Override
        public LlmResponse complete(LlmRequest request, String apiKey, String model, LlmCallOptions options) {
            lastRequest = request;
            lastKey = apiKey;
            lastModel = model;
            lastOptions = options;
            if (failWith != null) {
                throw failWith;
            }
            return new LlmResponse("{\"status\":\"ok\"}", id(), model);
        }
    }

    private LlmKeyRepository keyRepository;
    private LlmKeyEncryptionService encryptionService;
    private RecordingProvider provider;
    private LlmKeyService keyService;
    private UUID userId;
    private Set<String> configuredProviders;

    @BeforeEach
    public void setUp() {
        keyRepository = mock(LlmKeyRepository.class);
        encryptionService = new LlmKeyEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        LlmProperties props = new LlmProperties();
        LlmProperties.ProviderProperties openrouter = new LlmProperties.ProviderProperties();
        openrouter.setType("openai");
        openrouter.setLabel("OpenRouter");
        props.getProviders().put("openrouter", openrouter);
        LlmProperties.ProviderProperties local = new LlmProperties.ProviderProperties();
        local.setType("openai");
        local.setAllowNoKey(true);
        props.getProviders().put("ampere", local);
        configuredProviders = props.getProviders().keySet();
        provider = new RecordingProvider();
        Map<String, LlmProvider> providers = new HashMap<>();
        providers.put("openrouter", provider);
        providers.put("ampere", provider);
        keyService = new LlmKeyService(keyRepository, mock(UserRepository.class), encryptionService, props, providers);
        userId = UUID.randomUUID();
    }

    private CustomModelSpec spec(String provider) {
        return CustomModelSpec.of(provider, "anthropic/claude-haiku-5.5", "json-schema", "low", 256, "anthropic", configuredProviders);
    }

    private LlmKey activeKey(String provider, String rawKey) {
        LlmKey k = new LlmKey();
        k.setId(UUID.randomUUID());
        k.setProvider(provider);
        k.setStatus(LlmKeyStatus.ACTIVE);
        k.setPosition(1);
        k.setKeyCiphertext(encryptionService.encrypt(rawKey));
        return k;
    }

    @Test
    public void probeUsesTheUsersOwnKeyForThatProviderAndAppliesTheRowsKnobs() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(activeKey("gemini", "gm-1"), activeKey("openrouter", "sk-live")));

        TestKeyResponse res = keyService.testModel(userId, spec("openrouter"));

        assertTrue(res.ok(), res.error());
        assertTrue(res.message().contains("anthropic/claude-haiku-5.5"));
        assertEquals("sk-live", provider.lastKey, "the OpenRouter key, not the Gemini one");
        assertEquals("anthropic/claude-haiku-5.5", provider.lastModel);
        assertEquals(new LlmCallOptions("json-schema", "low", 256, "anthropic"), provider.lastOptions);
        assertNotNull(provider.lastRequest.responseSchema(), "the probe is schema-constrained like every real task");
    }

    @Test
    public void probeWithoutAKeyForThatProviderSaysWhichKeyToAdd() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(activeKey("gemini", "gm-1")));

        TestKeyResponse res = keyService.testModel(userId, spec("openrouter"));

        assertFalse(res.ok());
        assertEquals("Add a OpenRouter API key first.", res.error());
        assertNull(provider.lastModel, "no call is made without a key");
    }

    @Test
    public void probeOfAKeylessProviderRunsWithoutAKey() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of());

        TestKeyResponse res = keyService.testModel(userId, spec("ampere"));

        assertTrue(res.ok(), res.error());
        assertNull(provider.lastKey);
    }

    @Test
    public void providerRejectionIsTranslatedForTheSettingsScreen() {
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE)))
                .thenReturn(List.of(activeKey("openrouter", "sk-live")));
        provider.failWith = new LlmException(LlmException.Kind.FATAL, "openrouter", 404, null, "No endpoints found");

        TestKeyResponse res = keyService.testModel(userId, spec("openrouter"));

        assertFalse(res.ok());
        assertEquals("Model 'anthropic/claude-haiku-5.5' was not found on OpenRouter.", res.error());
    }

    @Test
    public void probeOfAProviderWithoutAnAdapterFailsCleanly() {
        LlmKeyService noProviders = new LlmKeyService(keyRepository, mock(UserRepository.class), encryptionService, new LlmProperties(), Map.of());
        TestKeyResponse res = noProviders.testModel(userId, spec("openrouter"));
        assertFalse(res.ok());
        assertTrue(res.error().contains("Provider not configured"), res.error());
    }
}
