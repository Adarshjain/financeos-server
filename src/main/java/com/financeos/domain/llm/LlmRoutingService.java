package com.financeos.domain.llm;

import com.financeos.api.llm.dto.*;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.BucketStateRegistry;
import com.financeos.llm.FailoverLlmClient;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.LlmTaskGroup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Resolves and persists a user's LLM routing order.
 *
 * <p>Everything a user can pick comes from {@code llm.routing-options} in config — provider names,
 * labels, pinned models and default chain order are all read from {@link LlmProperties}. Nothing in
 * this class hardcodes a model id or a chain, so adding a provider or retiring a model is a config
 * change and never a code change.
 */
@Service
public class LlmRoutingService {

    private final LlmProperties llmProperties;
    private final LlmTaskPrefRepository taskPrefRepository;
    private final LlmKeyRepository keyRepository;
    private final UserRepository userRepository;
    private final FailoverLlmClient llmClient;

    public LlmRoutingService(LlmProperties llmProperties,
                             LlmTaskPrefRepository taskPrefRepository,
                             LlmKeyRepository keyRepository,
                             UserRepository userRepository,
                             FailoverLlmClient llmClient) {
        this.llmProperties = llmProperties;
        this.taskPrefRepository = taskPrefRepository;
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.llmClient = llmClient;
    }

    // ---------------------------------------------------------------- providers & options

    public String getFriendlyProviderName(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "";
        }
        String normalized = providerId.trim().toLowerCase();
        LlmProperties.ProviderProperties props = providerProps(normalized);
        if (props != null && props.getLabel() != null && !props.getLabel().isBlank()) {
            return props.getLabel();
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private LlmProperties.ProviderProperties providerProps(String providerId) {
        if (llmProperties == null || llmProperties.getProviders() == null || providerId == null) {
            return null;
        }
        return llmProperties.getProviders().get(providerId.trim().toLowerCase());
    }

    private List<LlmProperties.RoutingOption> configuredOptions() {
        if (llmProperties == null || llmProperties.getRoutingOptions() == null) {
            return List.of();
        }
        return llmProperties.getRoutingOptions();
    }

    private LlmProperties.RoutingOption optionById(String id) {
        if (id == null) {
            return null;
        }
        return configuredOptions().stream()
                .filter(o -> id.trim().equalsIgnoreCase(o.getId()))
                .findFirst()
                .orElse(null);
    }

    public List<RoutingOptionDto> getRoutingOptions(UUID userId) {
        Set<String> available = getActiveProviderKeys(userId);
        List<RoutingOptionDto> out = new ArrayList<>();
        for (LlmProperties.RoutingOption o : configuredOptions()) {
            String provider = o.getProvider() != null ? o.getProvider().trim().toLowerCase() : "";
            LlmProperties.ProviderProperties props = providerProps(provider);
            LlmProperties.ModelEntry model = props != null ? props.findModel(o.getModel()) : null;
            out.add(new RoutingOptionDto(
                    o.getId(),
                    o.getLabel() != null ? o.getLabel() : o.getId(),
                    provider,
                    getFriendlyProviderName(provider),
                    o.getModel(),
                    o.getNotes(),
                    model != null && model.isFree(),
                    model != null && model.getTrainsOnData() != null ? model.getTrainsOnData() : "unknown",
                    available.contains(provider) || isAllowNoKey(provider)
            ));
        }
        return out;
    }

    public List<ProviderCatalogDto> getCatalog() {
        List<ProviderCatalogDto> list = new ArrayList<>();
        if (llmProperties == null || llmProperties.getProviders() == null) {
            return list;
        }
        for (Map.Entry<String, LlmProperties.ProviderProperties> entry : llmProperties.getProviders().entrySet()) {
            String pId = entry.getKey();
            LlmProperties.ProviderProperties props = entry.getValue();

            List<ModelCatalogEntryDto> models = new ArrayList<>();
            if (props.getModelCatalog() != null) {
                for (LlmProperties.ModelEntry me : props.getModelCatalog()) {
                    models.add(new ModelCatalogEntryDto(
                            me.getId(),
                            me.getLabel() != null ? me.getLabel() : me.getId(),
                            me.getStructuredOutput() != null ? me.getStructuredOutput() : props.getStructuredOutput(),
                            me.isFree(),
                            me.getTrainsOnData() != null ? me.getTrainsOnData() : "unknown",
                            me.getNotes()
                    ));
                }
            }
            list.add(new ProviderCatalogDto(pId, getFriendlyProviderName(pId), props.getType(), props.getModel(), models));
        }
        return list;
    }

    // ---------------------------------------------------------------- read routing

    public LlmRoutingDto getRouting(UUID userId) {
        return new LlmRoutingDto(
                getGroupRouting(userId, LlmTaskGroup.CHAT),
                getGroupRouting(userId, LlmTaskGroup.DEFAULT)
        );
    }

    /**
     * Every configured option, always, in the order this group will actually try them.
     *
     * <p>The user's saved order comes first; any option added to config since they last saved is
     * appended in its shipped position rather than silently promoted above their choices. Options
     * whose provider was removed from config are dropped. This mirrors
     * {@link FailoverLlmClient#resolveChain(UUID, String)} — including its deliberate absence of
     * cross-group inheritance — because a settings screen that disagrees with what runs is worse
     * than no settings screen.
     */
    public LlmRoutingGroupDto getGroupRouting(UUID userId, LlmTaskGroup group) {
        Set<String> activeProviderKeys = getActiveProviderKeys(userId);
        List<LlmTaskPref> userPrefs = userId != null
                ? taskPrefRepository.findByUserIdAndTaskGroupOrderByPositionAsc(userId, group)
                : Collections.emptyList();

        List<String> order = effectiveOptionOrder(group, userPrefs);

        List<RoutingEntryDto> entries = new ArrayList<>();
        int position = 1;
        for (String optionId : order) {
            LlmProperties.RoutingOption option = optionById(optionId);
            if (option == null) {
                continue;
            }
            String provider = option.getProvider() != null ? option.getProvider().trim().toLowerCase() : "";
            entries.add(new RoutingEntryDto(
                    position++,
                    option.getId(),
                    option.getLabel() != null ? option.getLabel() : option.getId(),
                    provider,
                    getFriendlyProviderName(provider),
                    option.getModel(),
                    activeProviderKeys.contains(provider) || isAllowNoKey(provider)
            ));
        }

        return new LlmRoutingGroupDto(
                group.getCode(),
                group.getDisplayName(),
                group.getDescription(),
                userPrefs.isEmpty(),
                entries
        );
    }

    /**
     * Saved order, then anything new from config. Keeping unsaved options at the tail means adding
     * a model to the menu can never quietly outrank a preference the user already expressed.
     */
    private List<String> effectiveOptionOrder(LlmTaskGroup group, List<LlmTaskPref> userPrefs) {
        List<String> configured = configuredOptions().stream().map(LlmProperties.RoutingOption::getId).toList();

        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (LlmTaskPref pref : userPrefs) {
            if (configured.stream().anyMatch(id -> id.equalsIgnoreCase(pref.getOptionId()))) {
                order.add(pref.getOptionId());
            }
        }
        for (String optionId : defaultOptionOrder(group)) {
            order.add(optionId);
        }
        order.addAll(configured);   // any option missing from both, so nothing is ever unreachable
        return new ArrayList<>(order);
    }

    /** The shipped order for a group, from {@code llm.default-routing}. */
    private List<String> defaultOptionOrder(LlmTaskGroup group) {
        if (llmProperties == null || llmProperties.getDefaultRouting() == null) {
            return List.of();
        }
        List<String> ids = llmProperties.getDefaultRouting().get(group.getCode());
        return ids != null ? ids : List.of();
    }

    // ---------------------------------------------------------------- write routing

    /**
     * Replaces a group's order. The payload must be a permutation of the entire option menu —
     * the UI only reorders, never adds or removes, and requiring the full set here makes a partial
     * save (which would leave an option silently unreachable) unrepresentable rather than merely
     * discouraged.
     */
    @Transactional
    public LlmRoutingGroupDto updateRouting(UUID userId, LlmTaskGroup group, List<RoutingEntryRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Routing list cannot be empty");
        }

        List<String> configured = configuredOptions().stream().map(LlmProperties.RoutingOption::getId).toList();
        List<String> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (RoutingEntryRequest entry : entries) {
            if (entry.optionId() == null || entry.optionId().isBlank()) {
                throw new IllegalArgumentException("Each routing entry needs an optionId");
            }
            LlmProperties.RoutingOption option = optionById(entry.optionId());
            if (option == null) {
                throw new IllegalArgumentException("Unknown routing option: " + entry.optionId());
            }
            if (!seen.add(option.getId().toLowerCase())) {
                throw new IllegalArgumentException("Duplicate routing option: " + option.getId());
            }
            resolved.add(option.getId());
        }

        List<String> missing = configured.stream()
                .filter(id -> !seen.contains(id.toLowerCase()))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Routing order must include every option. Missing: " + String.join(", ", missing));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Delete then flush before inserting: uq_llm_task_prefs (user_id, task_group, position)
        // would otherwise collide mid-flush when positions are reused.
        taskPrefRepository.deleteByUserIdAndTaskGroup(userId, group);
        taskPrefRepository.flush();

        List<LlmTaskPref> newPrefs = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            LlmTaskPref pref = new LlmTaskPref();
            pref.setUser(user);
            pref.setTaskGroup(group);
            pref.setPosition(i + 1);
            pref.setOptionId(resolved.get(i));
            newPrefs.add(pref);
        }
        taskPrefRepository.saveAll(newPrefs);

        return getGroupRouting(userId, group);
    }

    @Transactional
    public LlmRoutingGroupDto resetRouting(UUID userId, LlmTaskGroup group) {
        taskPrefRepository.deleteByUserIdAndTaskGroup(userId, group);
        taskPrefRepository.flush();
        return getGroupRouting(userId, group);
    }

    // ---------------------------------------------------------------- health

    /**
     * Cooldown state for every bucket the user's keys can produce. Bucket names come from
     * {@link FailoverLlmClient#bucketKeyFor} — the same helper the failover loop uses — so this can
     * never drift into reporting on buckets that are never created.
     */
    public List<LlmBucketHealthDto> getHealth(UUID userId) {
        List<LlmBucketHealthDto> healthList = new ArrayList<>();
        if (llmClient == null || llmClient.getBucketStateRegistry() == null) {
            return healthList;
        }

        BucketStateRegistry registry = llmClient.getBucketStateRegistry();
        List<LlmKey> activeKeys = userId != null
                ? keyRepository.findByUserIdAndStatusOrderByPositionAsc(userId, LlmKeyStatus.ACTIVE)
                : Collections.emptyList();

        for (LlmKey key : activeKeys) {
            String provider = key.getProvider().toLowerCase();
            for (String model : modelsReachableFor(provider)) {
                BucketStateRegistry.BucketCooldownInfo status =
                        registry.getBucketStatus(FailoverLlmClient.bucketKeyFor(key.getId(), model));
                healthList.add(new LlmBucketHealthDto(
                        provider,
                        getFriendlyProviderName(provider),
                        model,
                        getModelLabel(provider, model),
                        key.getKeyLast4(),
                        key.getLabel(),
                        status.inCooldown(),
                        status.cooldownUntil(),
                        status.consecutiveFailures()
                ));
            }
        }
        return healthList;
    }

    /**
     * Every model this provider can actually be invoked with: the models pinned by routing options,
     * plus the provider default, plus (for a chain-style provider) its full ordered model list.
     */
    private List<String> modelsReachableFor(String provider) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        LlmProperties.ProviderProperties props = providerProps(provider);

        for (LlmProperties.RoutingOption o : configuredOptions()) {
            if (o.getProvider() == null || !provider.equalsIgnoreCase(o.getProvider().trim())) {
                continue;
            }
            if (o.getModel() != null && !o.getModel().isBlank()) {
                models.add(o.getModel().trim());
            } else if (props != null && props.getModels() != null) {
                models.addAll(props.getModels());   // chain-style option: every model is reachable
            }
        }
        if (props != null && props.getModel() != null && !props.getModel().isBlank()) {
            models.add(props.getModel());
        }
        return new ArrayList<>(models);
    }

    // ---------------------------------------------------------------- helpers

    private Set<String> getActiveProviderKeys(UUID userId) {
        Set<String> set = new HashSet<>();
        if (userId != null && keyRepository != null) {
            for (LlmKey k : keyRepository.findByUserIdAndStatusOrderByPositionAsc(userId, LlmKeyStatus.ACTIVE)) {
                set.add(k.getProvider().toLowerCase());
            }
        }
        return set;
    }

    private boolean isAllowNoKey(String provider) {
        LlmProperties.ProviderProperties props = providerProps(provider);
        return props != null && props.isAllowNoKey();
    }

    private String getModelLabel(String providerId, String modelId) {
        LlmProperties.ProviderProperties props = providerProps(providerId);
        LlmProperties.ModelEntry entry = props != null ? props.findModel(modelId) : null;
        if (entry != null && entry.getLabel() != null && !entry.getLabel().isBlank()) {
            return entry.getLabel();
        }
        return modelId;
    }
}
