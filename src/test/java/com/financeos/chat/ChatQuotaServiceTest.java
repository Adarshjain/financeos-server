package com.financeos.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.chat.api.ChatQuotaService;
import com.financeos.chat.db.ChatProperties;
import org.junit.jupiter.api.Test;

class ChatQuotaServiceTest {

    @Test
    void onlyConcurrencyIsCappedAndThereIsNoDailyLimit() {
        ChatProperties props = new ChatProperties();
        props.getQuota().setMaxConcurrent(2);
        ChatQuotaService quota = new ChatQuotaService(props);

        // many sequential answers: the permit is released each time, so nothing ever runs out
        for (int i = 0; i < 100; i++) {
            assertTrue(quota.tryAcquireConcurrency());
            quota.releaseConcurrency();
        }

        assertTrue(quota.tryAcquireConcurrency());
        assertTrue(quota.tryAcquireConcurrency());
        assertFalse(quota.tryAcquireConcurrency());
        quota.releaseConcurrency();
        assertTrue(quota.tryAcquireConcurrency());
    }
}
