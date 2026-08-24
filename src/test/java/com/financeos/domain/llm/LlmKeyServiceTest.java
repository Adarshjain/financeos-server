package com.financeos.domain.llm;

import com.financeos.api.llm.dto.LlmKeyDto;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmProperties;
import com.financeos.llm.security.LlmKeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class LlmKeyServiceTest {

    private LlmKeyRepository keyRepository;
    private UserRepository userRepository;
    private LlmKeyEncryptionService encryptionService;
    private LlmProperties llmProperties;
    private LlmKeyService keyService;

    private UUID userId;
    private User user;

    @BeforeEach
    public void setUp() {
        keyRepository = mock(LlmKeyRepository.class);
        userRepository = mock(UserRepository.class);
        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        encryptionService = new LlmKeyEncryptionService(masterKey);
        llmProperties = new LlmProperties();

        keyService = new LlmKeyService(keyRepository, userRepository, encryptionService, llmProperties);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
    }

    @Test
    public void testTenancyEnforcementOnDeleteAndReorder() {
        UUID otherUserId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();

        when(keyRepository.findByIdAndUserId(eq(keyId), eq(userId))).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> keyService.deleteKey(userId, keyId));
        assertThrows(IllegalArgumentException.class, () -> keyService.updatePosition(userId, keyId, 2));
    }

    @Test
    public void testTwoPhaseUpdatePositionReorder() {
        UUID key1Id = UUID.randomUUID();
        LlmKey key1 = new LlmKey();
        key1.setId(key1Id);
        key1.setUser(user);
        key1.setProvider("gemini");
        key1.setPosition(1);
        key1.setStatus(LlmKeyStatus.ACTIVE);

        UUID key2Id = UUID.randomUUID();
        LlmKey key2 = new LlmKey();
        key2.setId(key2Id);
        key2.setUser(user);
        key2.setProvider("gemini");
        key2.setPosition(2);
        key2.setStatus(LlmKeyStatus.ACTIVE);

        when(keyRepository.findByIdAndUserId(eq(key1Id), eq(userId))).thenReturn(Optional.of(key1));
        when(keyRepository.findByUserIdAndProviderOrderByPositionAsc(eq(userId), eq("gemini")))
                .thenReturn(new ArrayList<>(List.of(key1, key2)));
        when(keyRepository.findByUserIdOrderByProviderAscPositionAsc(eq(userId)))
                .thenReturn(List.of(key2, key1));

        List<LlmKeyDto> result = keyService.updatePosition(userId, key1Id, 2);

        assertNotNull(result);
        // Verify saveAllAndFlush was called for phase 1 (temporary positions)
        verify(keyRepository, times(1)).saveAllAndFlush(any());
        // Verify saveAll was called for phase 2 (final positions)
        verify(keyRepository, times(1)).saveAll(any());

        assertEquals(2, key1.getPosition());
        assertEquals(1, key2.getPosition());
    }
}
