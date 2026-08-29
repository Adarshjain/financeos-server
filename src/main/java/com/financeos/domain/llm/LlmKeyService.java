package com.financeos.domain.llm;

import com.financeos.api.llm.dto.CreateLlmKeyRequest;
import com.financeos.api.llm.dto.LlmKeyDto;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.security.LlmKeyEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.llm.dto.TestKeyResponse;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmProvider;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;

@Service
public class LlmKeyService {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmKeyRepository keyRepository;
    private final UserRepository userRepository;
    private final LlmKeyEncryptionService encryptionService;
    private final LlmProperties llmProperties;
    private final Map<String, LlmProvider> providers;
    private final HttpClient httpClient;

    @Autowired
    public LlmKeyService(LlmKeyRepository keyRepository,
                         UserRepository userRepository,
                         LlmKeyEncryptionService encryptionService,
                         LlmProperties llmProperties,
                         Map<String, LlmProvider> providers) {
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.llmProperties = llmProperties;
        this.providers = providers;
        this.httpClient = HttpClient.newBuilder().build();
    }

    public LlmKeyService(LlmKeyRepository keyRepository,
                         UserRepository userRepository,
                         LlmKeyEncryptionService encryptionService,
                         LlmProperties llmProperties) {
        this(keyRepository, userRepository, encryptionService, llmProperties, null);
    }

    public List<LlmKeyDto> listKeys(UUID userId) {
        List<LlmKey> keys = keyRepository.findByUserIdOrderByProviderAscPositionAsc(userId);
        return keys.stream().map(LlmKeyDto::fromEntity).toList();
    }

    @Transactional
    public LlmKeyDto createKey(UUID userId, CreateLlmKeyRequest request) {
        if (encryptionService == null || !encryptionService.isConfigured()) {
            throw new IllegalStateException("Server is missing LLM_KEYS_MASTER_KEY — key storage is not configured.");
        }

        String provider = request.provider() != null ? request.provider().trim().toLowerCase() : "";
        Set<String> allowedProviders = llmProperties != null && llmProperties.getProviders() != null && !llmProperties.getProviders().isEmpty()
                ? llmProperties.getProviders().keySet()
                : Set.of("gemini", "groq", "openrouter");
        if (!allowedProviders.contains(provider)) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + request.provider() + ". Allowed: " + allowedProviders);
        }

        String rawKey = request.key() != null ? request.key().trim() : "";
        if (rawKey.isEmpty()) {
            throw new IllegalArgumentException("API key cannot be empty");
        }

        // Validate key against provider
        validateKeyWithProvider(provider, rawKey);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        int nextPosition = keyRepository.findFirstByUserIdAndProviderOrderByPositionDesc(userId, provider)
                .map(k -> k.getPosition() + 1)
                .orElse(1);

        String ciphertext = encryptionService.encrypt(rawKey);
        String last4 = LlmKeyEncryptionService.extractLast4(rawKey);

        LlmKey key = new LlmKey();
        key.setUser(user);
        key.setProvider(provider);
        key.setKeyCiphertext(ciphertext);
        key.setKeyLast4(last4);
        key.setLabel(request.label() != null && !request.label().isBlank() ? request.label().trim() : null);
        key.setPosition(nextPosition);
        key.setStatus(LlmKeyStatus.ACTIVE);

        LlmKey saved = keyRepository.save(key);
        log.info("Saved new LLM API key {} (provider={}, last4={}) for user {}", saved.getId(), provider, last4, userId);
        return LlmKeyDto.fromEntity(saved);
    }

    @Transactional
    public void deleteKey(UUID userId, UUID keyId) {
        LlmKey key = keyRepository.findByIdAndUserId(keyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Key not found or access denied: " + keyId));

        String provider = key.getProvider();
        keyRepository.delete(key);

        // Recompact positions with two-phase update to avoid unique constraint collisions mid-flush
        List<LlmKey> remaining = keyRepository.findByUserIdAndProviderOrderByPositionAsc(userId, provider);
        if (!remaining.isEmpty()) {
            for (int i = 0; i < remaining.size(); i++) {
                remaining.get(i).setPosition(1000 + i + 1);
            }
            keyRepository.saveAllAndFlush(remaining);

            for (int i = 0; i < remaining.size(); i++) {
                remaining.get(i).setPosition(i + 1);
            }
            keyRepository.saveAll(remaining);
        }
        log.info("Deleted LLM API key {} for user {}", keyId, userId);
    }

    @Transactional
    public List<LlmKeyDto> updatePosition(UUID userId, UUID keyId, int targetPosition) {
        LlmKey targetKey = keyRepository.findByIdAndUserId(keyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Key not found or access denied: " + keyId));

        String provider = targetKey.getProvider();
        List<LlmKey> keys = keyRepository.findByUserIdAndProviderOrderByPositionAsc(userId, provider);

        if (keys.isEmpty()) {
            return List.of();
        }

        List<LlmKey> updated = new ArrayList<>(keys);
        updated.removeIf(k -> k.getId().equals(keyId));

        int newIdx = Math.max(0, Math.min(targetPosition - 1, updated.size()));
        updated.add(newIdx, targetKey);

        // Phase 1: Shift to temp positions out-of-range to avoid unique constraint collision mid-flush
        for (int i = 0; i < updated.size(); i++) {
            updated.get(i).setPosition(1000 + i + 1);
        }
        keyRepository.saveAllAndFlush(updated);

        // Phase 2: Assign final contiguous 1..N positions
        for (int i = 0; i < updated.size(); i++) {
            updated.get(i).setPosition(i + 1);
        }
        keyRepository.saveAll(updated);

        return listKeys(userId);
    }

    private void validateKeyWithProvider(String provider, String rawKey) {
        try {
            String url;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(10));

            if ("gemini".equalsIgnoreCase(provider)) {
                url = "https://generativelanguage.googleapis.com/v1beta/models";
                reqBuilder.uri(URI.create(url)).header("x-goog-api-key", rawKey).GET();
            } else if ("openrouter".equalsIgnoreCase(provider)) {
                url = "https://openrouter.ai/api/v1/key";
                reqBuilder.uri(URI.create(url)).header("Authorization", "Bearer " + rawKey).GET();
            } else {
                String baseUrl = getBaseUrl(provider);
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                url = baseUrl + "/models";
                reqBuilder.uri(URI.create(url)).header("Authorization", "Bearer " + rawKey).GET();
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalArgumentException("Invalid API key for provider '" + provider + "': Authentication failed (HTTP " + response.statusCode() + "). Verify your key.");
            } else if (response.statusCode() != 200) {
                log.warn("Validation request for provider {} returned status {}", provider, response.statusCode());
                throw new IllegalArgumentException("Failed to validate key with " + provider + ": HTTP " + response.statusCode());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error validating key for provider {}", provider, e);
            throw new IllegalArgumentException("Could not connect to provider '" + provider + "' to validate API key: " + e.getMessage());
        }
    }

    public TestKeyResponse testKey(UUID userId, UUID keyId, String requestedModel) {
        if (encryptionService == null || !encryptionService.isConfigured()) {
            return TestKeyResponse.failure("Key storage is not configured.");
        }

        LlmKey key = keyRepository.findByIdAndUserId(keyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Key not found or access denied: " + keyId));

        String apiKey;
        try {
            apiKey = encryptionService.decrypt(key.getKeyCiphertext());
        } catch (Exception e) {
            return TestKeyResponse.failure("Failed to decrypt key: " + e.getMessage());
        }

        String providerId = key.getProvider().toLowerCase();
        LlmProvider provider = providers != null ? providers.get(providerId) : null;
        if (provider == null) {
            return TestKeyResponse.failure("Provider not configured: " + providerId);
        }

        String model = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel.trim()
                : (llmProperties != null && llmProperties.getProviders() != null && llmProperties.getProviders().containsKey(providerId)
                ? llmProperties.getProviders().get(providerId).getModel()
                : null);

        // Send a real schema, not a bare ping: every task this key will serve is schema-constrained,
        // so "the key works" is only useful if it also proves the model honours structured output.
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("status").put("type", "string");
        schema.putArray("required").add("status");

        try {
            LlmRequest req = new LlmRequest("test", "Reply with {\"status\":\"ok\"}.", schema, 0.0);
            provider.complete(req, apiKey, model);
            return TestKeyResponse.success("Model '" + (model != null ? model : providerId) + "' responded successfully");
        } catch (LlmException e) {
            log.warn("Test completion failed for key {} provider {} model {}: {}", keyId, providerId, model, e.getMessage());
            return TestKeyResponse.failure(describeFailure(e, providerId, model));
        } catch (Exception e) {
            log.warn("Test completion failed for key {} provider {} model {}: {}", keyId, providerId, model, e.getMessage());
            return TestKeyResponse.failure(e.getMessage() != null ? e.getMessage() : "Unknown error during test completion");
        }
    }

    /**
     * Turns a provider's raw HTTP body into something a user can act on. The underlying detail is
     * already in the logs; what the settings screen needs is which of "wrong key", "no credit",
     * "rate limited" or "bad request" happened.
     */
    private String describeFailure(LlmException e, String providerId, String model) {
        Integer status = e.getStatusCode();
        String provider = getFriendlyName(providerId);
        if (status == null) {
            return e.getMessage() != null ? e.getMessage() : "Could not reach " + provider + ".";
        }
        return switch (status) {
            case 400 -> provider + " rejected the request for model '" + model
                    + "'. The model may not exist on this account or may not support structured output.";
            case 401, 403 -> provider + " rejected this API key. Check that it is correct and still active.";
            case 402 -> provider + " requires payment for this model. Add credit or a payment method in your "
                    + provider + " billing settings.";
            case 404 -> "Model '" + model + "' was not found on " + provider + ".";
            case 429 -> provider + " rate-limited this key. It may be out of quota for now — try again later.";
            default -> status >= 500
                    ? provider + " is having a problem right now (HTTP " + status + "). Try again shortly."
                    : provider + " returned HTTP " + status + ".";
        };
    }

    private String getFriendlyName(String providerId) {
        if (llmProperties != null && llmProperties.getProviders() != null) {
            LlmProperties.ProviderProperties props = llmProperties.getProviders().get(providerId);
            if (props != null && props.getLabel() != null && !props.getLabel().isBlank()) {
                return props.getLabel();
            }
        }
        return providerId;
    }

    private String getBaseUrl(String provider) {
        if (llmProperties != null && llmProperties.getProviders() != null && llmProperties.getProviders().containsKey(provider)) {
            LlmProperties.ProviderProperties props = llmProperties.getProviders().get(provider);
            if (props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
                return props.getBaseUrl().trim();
            }
        }
        return switch (provider) {
            case "groq" -> "https://api.groq.com/openai/v1";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> throw new IllegalArgumentException("Unknown base URL for provider: " + provider);
        };
    }
}
