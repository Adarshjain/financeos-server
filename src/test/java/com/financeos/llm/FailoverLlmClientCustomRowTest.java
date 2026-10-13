package com.financeos.llm;

import com.financeos.domain.llm.LlmKey;
import com.financeos.domain.llm.LlmKeyRepository;
import com.financeos.domain.llm.LlmKeyStatus;
import com.financeos.domain.llm.LlmTaskPref;
import com.financeos.domain.llm.LlmTaskPrefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A custom routing row's knobs must arrive at the provider call, and menu rows must arrive with none. */
public class FailoverLlmClientCustomRowTest {

    /** Records what the failover loop actually passed. */
    private static class RecordingProvider implements LlmProvider {
        final List<String> models = new ArrayList<>();
        final List<LlmCallOptions> options = new ArrayList<>();

        @Override
        public String id() {
            return "openrouter";
        }

        @Override
        public LlmResponse complete(LlmRequest request, String apiKey, String model) {
            throw new AssertionError("the failover loop must call the options-aware overload");
        }

        @Override
        public LlmResponse complete(LlmRequest request, String apiKey, String model, LlmCallOptions options) {
            this.models.add(model);
            this.options.add(options);
            return new LlmResponse("{}", id(), model);
        }
    }

    private LlmProperties properties;
    private LlmTaskPrefRepository taskPrefRepository;
    private LlmKeyRepository keyRepository;
    private RecordingProvider provider;
    private FailoverLlmClient client;
    private UUID userId;

    @BeforeEach
    public void setUp() {
        properties = new LlmProperties();
        properties.getRetry().setAttemptsPerProvider(1);
        LlmProperties.ProviderProperties openrouter = new LlmProperties.ProviderProperties();
        openrouter.setType("openai");
        openrouter.setModel("z-ai/glm-5.3-flash");
        properties.getProviders().put("openrouter", openrouter);
        LlmProperties.RoutingOption glm = new LlmProperties.RoutingOption();
        glm.setId("openrouter-glm");
        glm.setProvider("openrouter");
        glm.setModel("z-ai/glm-5.3-flash");
        properties.setRoutingOptions(List.of(glm));

        taskPrefRepository = mock(LlmTaskPrefRepository.class);
        keyRepository = mock(LlmKeyRepository.class);
        provider = new RecordingProvider();
        client = new FailoverLlmClient(properties, Map.of("openrouter", provider), keyRepository, taskPrefRepository, null, null, new BucketStateRegistry());

        userId = UUID.randomUUID();
        LlmKey key = new LlmKey();
        key.setId(UUID.randomUUID());
        key.setProvider("openrouter");
        key.setStatus(LlmKeyStatus.ACTIVE);
        key.setPosition(1);
        when(keyRepository.findByUserIdAndStatusOrderByPositionAsc(eq(userId), eq(LlmKeyStatus.ACTIVE))).thenReturn(List.of(key));
    }

    private static LlmTaskPref customPref(String model, int position) {
        LlmTaskPref p = new LlmTaskPref();
        p.setOptionId("custom");
        p.setProvider("openrouter");
        p.setModel(model);
        p.setPosition(position);
        p.setReasoningEffort("low");
        p.setMaxOutputTokens(512);
        return p;
    }

    @Test
    public void providerReceivesTheCustomRowsKnobsAndTheTypedModel() {
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("anthropic/claude-haiku-5.5", 1)));

        LlmResponse res = client.complete(new LlmRequest(userId, "categorize", "prompt", null, 0.0));

        assertEquals("anthropic/claude-haiku-5.5", res.model());
        assertEquals(List.of("anthropic/claude-haiku-5.5"), provider.models);
        assertEquals(new LlmCallOptions(null, "low", 512, null), provider.options.get(0));
    }

    @Test
    public void menuRowsAfterACustomRowStillArriveWithNoKnobs() {
        LlmTaskPref menu = new LlmTaskPref();
        menu.setOptionId("openrouter-glm");
        menu.setPosition(2);
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("foo/bar", 1), menu));
        List<FailoverLlmClient.BucketTarget> buckets = client.buildBucketListForTest(userId, client.resolveChain(userId, "categorize"));

        assertEquals(List.of("foo/bar", "z-ai/glm-5.3-flash"), buckets.stream().map(FailoverLlmClient.BucketTarget::model).toList());
        assertEquals(new LlmCallOptions(null, "low", 512, null), buckets.get(0).options());
        assertEquals(LlmCallOptions.NONE, buckets.get(1).options());
    }

    @Test
    public void twoArgChainEntryAndFourArgBucketTargetDefaultToNoKnobs() {
        assertEquals(LlmCallOptions.NONE, new FailoverLlmClient.ChainEntry("openrouter", "m").options());
        assertEquals(LlmCallOptions.NONE, new FailoverLlmClient.BucketTarget(null, "openrouter", "m", "k").options());
    }

    @Test
    public void theSameTypedModelOnTwoRowsSharesOneBucket() {
        // Dedupe is by (key, model): a custom row and a menu option naming the same model must not
        // queue the same credential twice, whatever knobs the custom row carries.
        LlmTaskPref menu = new LlmTaskPref();
        menu.setOptionId("openrouter-glm");
        menu.setPosition(2);
        when(taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(eq(userId), eq(LlmTaskGroup.DEFAULT)))
                .thenReturn(List.of(customPref("z-ai/glm-5.3-flash", 1), menu));

        List<FailoverLlmClient.BucketTarget> buckets = client.buildBucketListForTest(userId, client.resolveChain(userId, "categorize"));

        assertEquals(1, buckets.size());
        assertEquals(new LlmCallOptions(null, "low", 512, null), buckets.get(0).options(), "the first (custom) row wins");
    }
}
