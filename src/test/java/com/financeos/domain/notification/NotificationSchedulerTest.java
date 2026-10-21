package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationSchedulerTest {

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
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

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, accounts, List.of(bills, emi), true).runTick();

        assertEquals(new NotificationScheduler.Summary(3, 3, 2, 1, 1), summary);
        assertNull(UserContext.getCurrentUserId(), "pooled thread must not leak the last user");
    }

    @Test
    void oneProducerFailingDoesNotStopTheOthersForThatUser() {
        UUID user = UUID.randomUUID();
        when(bills.evaluate(user)).thenThrow(new IllegalStateException("boom"));
        when(emi.evaluate(user)).thenReturn(new NotificationOutcome(3, 2, 2));

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, accounts, List.of(bills, emi), true).runProducers(user);

        assertEquals(new NotificationScheduler.Summary(1, 3, 2, 2, 1), summary);
        verify(emi).evaluate(user);
    }

    @Test
    void runProducersSumsEveryProducer() {
        UUID user = UUID.randomUUID();
        when(bills.evaluate(user)).thenReturn(new NotificationOutcome(1, 1, 1));
        when(emi.evaluate(user)).thenReturn(new NotificationOutcome(2, 1, 0));

        assertEquals(new NotificationScheduler.Summary(1, 3, 2, 1, 0),
                new NotificationScheduler(repository, accounts, List.of(bills, emi), true).runProducers(user));
    }

    @Test
    void disabledSchedulerDoesNothing() {
        assertNull(new NotificationScheduler(repository, accounts, List.of(bills), false).runTick());
        verify(repository, never()).findByPushEnabledTrueAndPushSubscriptionsIsNotNull();
        verify(bills, never()).evaluate(any());
    }

    @Test
    void tickWithNoReachableUsersIsAnEmptySummary() {
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of());
        assertEquals(new NotificationScheduler.Summary(0, 0, 0, 0, 0),
                new NotificationScheduler(repository, accounts, List.of(bills), true).runTick());
        verify(bills, never()).evaluate(any());
    }

    @Test
    void cardHoldersWithoutPushOnlyGetTheFeedProducersAndReachableUsersGetEverything() {
        UUID reachable = UUID.randomUUID();
        UUID noPush = UUID.randomUUID();
        NotificationProducer rewards = producer("rewards");
        when(rewards.runsWithoutPush()).thenReturn(true);
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of(new UserNotificationSettings(reachable)));
        when(accounts.findDistinctUserIdsByType(AccountType.credit_card)).thenReturn(List.of(reachable, noPush));
        when(bills.evaluate(any())).thenReturn(NotificationOutcome.NONE);
        when(rewards.evaluate(reachable)).thenReturn(new NotificationOutcome(1, 1, 1));
        when(rewards.evaluate(noPush)).thenAnswer(inv -> {
            assertEquals(noPush, UserContext.getCurrentUserId());
            return new NotificationOutcome(1, 1, 0);
        });

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, accounts, List.of(bills, rewards), true).runTick();

        assertEquals(new NotificationScheduler.Summary(2, 2, 2, 1, 0), summary);
        verify(bills).evaluate(reachable);
        verify(bills, never()).evaluate(noPush);
        verify(rewards, org.mockito.Mockito.times(1)).evaluate(reachable);
        verify(rewards).evaluate(noPush);
        assertNull(UserContext.getCurrentUserId());
    }

    @Test
    void feedProducerFailureForANonPushUserIsCountedAndDoesNotStopTheTick() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        NotificationProducer rewards = producer("rewards");
        when(rewards.runsWithoutPush()).thenReturn(true);
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of());
        when(accounts.findDistinctUserIdsByType(AccountType.credit_card)).thenReturn(List.of(a, b));
        when(rewards.evaluate(a)).thenThrow(new IllegalStateException("boom"));
        when(rewards.evaluate(b)).thenReturn(new NotificationOutcome(1, 1, 0));

        assertEquals(new NotificationScheduler.Summary(2, 1, 1, 0, 1),
                new NotificationScheduler(repository, accounts, List.of(bills, rewards), true).runTick());
        verify(bills, never()).evaluate(any());
    }

    @Test
    void nonPushUsersAreNotQueriedWhenNoProducerRunsWithoutPush() {
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of());
        new NotificationScheduler(repository, accounts, List.of(bills), true).runTick();
        verify(accounts, never()).findDistinctUserIdsByType(any());
    }

    @Test
    void failingCardHolderLookupIsCountedAndReachableUsersStillRun() {
        UUID a = UUID.randomUUID();
        NotificationProducer rewards = producer("rewards");
        when(rewards.runsWithoutPush()).thenReturn(true);
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of(new UserNotificationSettings(a)));
        when(accounts.findDistinctUserIdsByType(AccountType.credit_card)).thenThrow(new IllegalStateException("db down"));
        when(bills.evaluate(a)).thenReturn(new NotificationOutcome(1, 1, 1));
        when(rewards.evaluate(a)).thenReturn(NotificationOutcome.NONE);

        assertEquals(new NotificationScheduler.Summary(1, 1, 1, 1, 1),
                new NotificationScheduler(repository, accounts, List.of(bills, rewards), true).runTick());
    }
}
