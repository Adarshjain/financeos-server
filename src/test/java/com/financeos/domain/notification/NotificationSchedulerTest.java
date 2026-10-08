package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationSchedulerTest {

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final NotificationProducer bills = producer("bills");
    private final NotificationProducer emi = producer("emi");

    private static NotificationProducer producer(String name) {
        NotificationProducer p = mock(NotificationProducer.class);
        when(p.name()).thenReturn(name);
        return p;
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void visitsEveryReachableUserInItsOwnContextAndKeepsGoingOnFailure() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull())
                .thenReturn(List.of(new UserNotificationSettings(a), new UserNotificationSettings(b), new UserNotificationSettings(c)));
        when(bills.evaluate(a)).thenAnswer(inv -> {
            assertEquals(a, UserContext.getCurrentUserId());
            return new NotificationOutcome(2, 1, 1);
        });
        when(bills.evaluate(b)).thenThrow(new IllegalStateException("db down"));
        when(bills.evaluate(c)).thenAnswer(inv -> {
            assertEquals(c, UserContext.getCurrentUserId());
            return new NotificationOutcome(1, 1, 0);
        });
        when(emi.evaluate(any())).thenReturn(NotificationOutcome.NONE);

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, List.of(bills, emi), true).runTick();

        assertEquals(new NotificationScheduler.Summary(3, 3, 2, 1, 1), summary);
        assertNull(UserContext.getCurrentUserId(), "pooled thread must not leak the last user");
    }

    @Test
    void oneProducerFailingDoesNotStopTheOthersForThatUser() {
        UUID user = UUID.randomUUID();
        when(bills.evaluate(user)).thenThrow(new IllegalStateException("boom"));
        when(emi.evaluate(user)).thenReturn(new NotificationOutcome(3, 2, 2));

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, List.of(bills, emi), true).runProducers(user);

        assertEquals(new NotificationScheduler.Summary(1, 3, 2, 2, 1), summary);
        verify(emi).evaluate(user);
    }

    @Test
    void runProducersSumsEveryProducer() {
        UUID user = UUID.randomUUID();
        when(bills.evaluate(user)).thenReturn(new NotificationOutcome(1, 1, 1));
        when(emi.evaluate(user)).thenReturn(new NotificationOutcome(2, 1, 0));

        assertEquals(new NotificationScheduler.Summary(1, 3, 2, 1, 0),
                new NotificationScheduler(repository, List.of(bills, emi), true).runProducers(user));
    }

    @Test
    void disabledSchedulerDoesNothing() {
        assertNull(new NotificationScheduler(repository, List.of(bills), false).runTick());
        verify(repository, never()).findByPushEnabledTrueAndPushSubscriptionsIsNotNull();
        verify(bills, never()).evaluate(any());
    }

    @Test
    void tickWithNoReachableUsersIsAnEmptySummary() {
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of());
        assertEquals(new NotificationScheduler.Summary(0, 0, 0, 0, 0),
                new NotificationScheduler(repository, List.of(bills), true).runTick());
        verify(bills, never()).evaluate(any());
    }
}
