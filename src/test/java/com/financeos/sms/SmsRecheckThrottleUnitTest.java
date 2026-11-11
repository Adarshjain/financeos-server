package com.financeos.sms;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.sms.ingest.SmsRecheckThrottle;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmsRecheckThrottleUnitTest {

    @Test
    void oneRunPerIntervalAndAFailedRunGivesTheSlotBack() {
        SmsRecheckThrottle t = new SmsRecheckThrottle(Duration.ofMinutes(10));
        UUID user = UUID.randomUUID();
        assertThat(t.tryAcquire(user)).isTrue();
        assertThat(t.tryAcquire(user)).isFalse();
        assertThat(t.tryAcquire(UUID.randomUUID())).isTrue();
        t.release(user);
        assertThat(t.tryAcquire(user)).isTrue();
        assertThat(t.tryAcquire(user)).isFalse();
    }

    @Test
    void aZeroIntervalAlwaysRuns() {
        SmsRecheckThrottle t = new SmsRecheckThrottle(Duration.ZERO);
        UUID user = UUID.randomUUID();
        assertThat(t.tryAcquire(user)).isTrue();
        assertThat(t.tryAcquire(user)).isTrue();
    }
}
