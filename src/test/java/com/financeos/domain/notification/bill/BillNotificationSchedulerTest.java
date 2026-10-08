package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.UserNotificationSettingsRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BillNotificationSchedulerTest {

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final BillNotificationService service = mock(BillNotificationService.class);

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
        when(service.evaluateUser(a)).thenAnswer(inv -> {
            assertEquals(a, UserContext.getCurrentUserId());
            return new BillNotificationService.Outcome(2, 1, 1);
        });
        when(service.evaluateUser(b)).thenThrow(new IllegalStateException("db down"));
        when(service.evaluateUser(c)).thenAnswer(inv -> {
            assertEquals(c, UserContext.getCurrentUserId());
            return new BillNotificationService.Outcome(1, 1, 0);
        });

        BillNotificationScheduler.Summary summary = new BillNotificationScheduler(repository, service, true).runTick();

        assertEquals(new BillNotificationScheduler.Summary(3, 3, 2, 1, 1), summary);
        assertNull(UserContext.getCurrentUserId(), "pooled thread must not leak the last user");
    }

    @Test
    void disabledSchedulerDoesNothing() {
        assertNull(new BillNotificationScheduler(repository, service, false).runTick());
        verify(repository, never()).findByPushEnabledTrueAndPushSubscriptionsIsNotNull();
        verify(service, never()).evaluateUser(any());
    }
}
