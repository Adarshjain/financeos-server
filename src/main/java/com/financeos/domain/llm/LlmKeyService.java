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

@Service
public class LlmKeyService {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyService.class);
    private static final Set<String> ALLOWED_PROVIDERS = Set.of("gemini", "cerebras", "groq", "openrouter");

    private final LlmKeyRepository keyRepository;
    private final UserRepository userRepository;
    private final LlmKeyEncryptionService encryptionService;
    private final LlmProperties llmProperties;
    private final HttpClient httpClient;

    public LlmKeyService(LlmKeyRepository keyRepository,
                         UserRepository userRepository,
                         LlmKeyEncryptionService encryptionService,
                         LlmProperties llmProperties) {
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.llmProperties = llmProperties;
        this.httpClient = HttpClient.newBuilder().build();
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
        if (!ALLOWED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + request.provider() + ". Allowed: " + ALLOWED_PROVIDERS);
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

    private String getBaseUrl(String provider) {
        if (llmProperties != null && llmProperties.getProviders() != null && llmProperties.getProviders().containsKey(provider)) {
            LlmProperties.ProviderProperties props = llmProperties.getProviders().get(provider);
            if (props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
                return props.getBaseUrl().trim();
            }
        }
        return switch (provider) {
            case "cerebras" -> "https://api.cerebras.ai/v1";
            case "groq" -> "https://api.groq.com/openai/v1";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> throw new IllegalArgumentException("Unknown base URL for provider: " + provider);
        };
    }
}
