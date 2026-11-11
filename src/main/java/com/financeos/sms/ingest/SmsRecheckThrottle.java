package com.financeos.sms.ingest;

import com.financeos.core.time.AppTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At most one re-check of undecided generic readings per user per interval (kept in memory, per server). */
@Component
public class SmsRecheckThrottle {

    private final Map<UUID, Instant> lastRun = new ConcurrentHashMap<>();
    private volatile Duration minInterval;

    public SmsRecheckThrottle(@Value("${financeos.sms.recheck-min-interval:PT10M}") Duration minInterval) {
        this.minInterval = minInterval;
    }

    /** True when the user's re-check may run now; records the run. */
    public boolean tryAcquire(UUID userId) {
        Instant now = AppTime.nowInstant();
        boolean[] run = {false};
        lastRun.compute(userId, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(minInterval))) {
                run[0] = true;
                return now;
            }
            return last;
        });
        return run[0];
    }

    /** Gives the slot back when the whole re-check failed, so the next batch may try again. */
    public void release(UUID userId) {
        lastRun.remove(userId);
    }

    public void setMinInterval(Duration minInterval) {
        this.minInterval = minInterval;
        lastRun.clear();
    }
}
