package com.financeos.sms.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmsLoginLimiterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final SmsLoginLimiter limiter = new SmsLoginLimiter();

    private int acquire(String key, int times, Instant at) {
        int allowed = 0;
        for (int i = 0; i < times; i++) {
            if (limiter.tryAcquire(key, at)) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void allowsExactlyTheCapWithoutASuccess() {
        assertThat(acquire(SmsLoginLimiter.passwordKey("a@example.test"), 20, T0)).isEqualTo(SmsLoginLimiter.MAX_FAILURES);
    }

    @Test
    void parallelAttemptsOnOneKeyNeverOvershootTheCap() throws Exception {
        String key = SmsLoginLimiter.passwordKey("a@example.test");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                start.await();
                if (limiter.tryAcquire(key, T0)) {
                    allowed.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(allowed.get()).isEqualTo(SmsLoginLimiter.MAX_FAILURES);
    }

    @Test
    void windowPassingFreesTheKeyAndSuccessClearsIt() {
        String key = SmsLoginLimiter.passwordKey("a@example.test");
        acquire(key, 20, T0);
        assertThat(limiter.retryAfterSeconds(key, T0.plusSeconds(60))).isBetween(1L, SmsLoginLimiter.WINDOW.getSeconds());
        assertThat(limiter.tryAcquire(key, T0.plus(SmsLoginLimiter.WINDOW).plusSeconds(1))).isTrue();
        acquire(key, 20, T0.plus(SmsLoginLimiter.WINDOW).plusSeconds(2));
        limiter.clear(key);
        assertThat(limiter.tryAcquire(key, T0.plus(SmsLoginLimiter.WINDOW).plusSeconds(3))).isTrue();
    }

    @Test
    void releaseGivesTheReservationBack() {
        String key = SmsLoginLimiter.googleKey("sub-1");
        assertThat(acquire(key, SmsLoginLimiter.MAX_FAILURES, T0)).isEqualTo(SmsLoginLimiter.MAX_FAILURES);
        assertThat(limiter.tryAcquire(key, T0)).isFalse();
        limiter.release(key);
        assertThat(limiter.tryAcquire(key, T0)).isTrue();
        assertThat(limiter.tryAcquire(key, T0)).isFalse();
    }

    @Test
    void oneKeyNeverLocksAnother() {
        acquire(SmsLoginLimiter.passwordKey("a@example.test"), 50, T0);
        assertThat(limiter.tryAcquire(SmsLoginLimiter.passwordKey("b@example.test"), T0)).isTrue();
        assertThat(limiter.tryAcquire(SmsLoginLimiter.googleKey("sub-1"), T0)).isTrue();
    }

    @Test
    void emailKeyIgnoresCaseAndSurroundingSpaces() {
        assertThat(SmsLoginLimiter.passwordKey(" A@Example.TEST ")).isEqualTo(SmsLoginLimiter.passwordKey("a@example.test"));
    }

    @Test
    void subjectsThatAreNotPlainIdsShareTheUnparsedBucket() {
        assertThat(SmsLoginLimiter.googleKey("1098765432109876543210")).isEqualTo("g:1098765432109876543210");
        assertThat(SmsLoginLimiter.googleKey("a".repeat(64))).startsWith("g:");
        for (String bad : new String[] {null, "", " ", "a".repeat(65), "has space", "semi;colon", "ünï", "pw:victim@example.test", "x\ny"}) {
            assertThat(SmsLoginLimiter.googleKey(bad)).isEqualTo(SmsLoginLimiter.UNPARSED_KEY);
        }
        String key = SmsLoginLimiter.UNPARSED_KEY;
        assertThat(acquire(key, 50, T0)).isEqualTo(SmsLoginLimiter.MAX_UNPARSED_FAILURES);
    }

    @Test
    void theMapIsBoundedAndForgedGoogleKeysAreEvictedBeforePasswordKeys() {
        String victim = SmsLoginLimiter.passwordKey("victim@example.test");
        acquire(victim, 20, T0);
        for (int i = 0; i < SmsLoginLimiter.MAX_KEYS + 50; i++) {
            limiter.tryAcquire("g:forged" + i, T0);
        }
        assertThat(limiter.size()).isEqualTo(SmsLoginLimiter.MAX_KEYS);
        assertThat(limiter.tryAcquire(victim, T0)).isFalse();
    }

    @Test
    void passwordKeysAreEvictedOnlyWhenNoGoogleKeyIsLeft() {
        for (int i = 0; i < SmsLoginLimiter.MAX_KEYS + 5; i++) {
            limiter.tryAcquire("pw:user" + i, T0);
        }
        assertThat(limiter.size()).isEqualTo(SmsLoginLimiter.MAX_KEYS);
        limiter.tryAcquire("pw:late", T0.plus(Duration.ofHours(1)));
        assertThat(limiter.size()).isEqualTo(1);
    }
}
