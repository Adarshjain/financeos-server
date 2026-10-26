package com.financeos.chat.api;

import com.financeos.chat.db.ChatProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/** Caps how many chat answers run at once (there is deliberately no per-user daily limit). */
@Service
public class ChatQuotaService {

    private final Semaphore concurrencySemaphore;

    public ChatQuotaService(ChatProperties chatProperties) {
        this.concurrencySemaphore = new Semaphore(chatProperties.getQuota().getMaxConcurrent());
    }

    public boolean tryAcquireConcurrency() {
        return concurrencySemaphore.tryAcquire();
    }

    public void releaseConcurrency() {
        concurrencySemaphore.release();
    }
}
