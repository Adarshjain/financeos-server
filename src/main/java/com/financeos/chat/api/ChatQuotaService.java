package com.financeos.chat.api;

import com.financeos.chat.db.ChatProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatQuotaService {

    private final ChatProperties chatProperties;
    private final Map<UUID, UserDailyCount> dailyCounts = new ConcurrentHashMap<>();
    private final Semaphore concurrencySemaphore;

    public ChatQuotaService(ChatProperties chatProperties) {
        this.chatProperties = chatProperties;
        this.concurrencySemaphore = new Semaphore(chatProperties.getQuota().getMaxConcurrent());
    }

    public boolean tryConsumeMessageQuota(UUID userId) {
        if (userId == null) {
            return false;
        }

        LocalDate today = LocalDate.now();
        int maxPerDay = chatProperties.getQuota().getMessagesPerDay();

        boolean[] consumed = new boolean[1];
        dailyCounts.compute(userId, (id, existing) -> {
            if (existing == null || !existing.date().equals(today)) {
                consumed[0] = true;
                return new UserDailyCount(today, new AtomicInteger(1));
            }
            if (existing.count().get() >= maxPerDay) {
                consumed[0] = false;
                return existing;
            }
            existing.count().incrementAndGet();
            consumed[0] = true;
            return existing;
        });

        return consumed[0];
    }

    public boolean tryAcquireConcurrency() {
        return concurrencySemaphore.tryAcquire();
    }

    public void releaseConcurrency() {
        concurrencySemaphore.release();
    }

    private record UserDailyCount(LocalDate date, AtomicInteger count) {}
}
