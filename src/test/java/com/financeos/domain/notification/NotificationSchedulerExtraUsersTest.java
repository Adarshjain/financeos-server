package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A feed producer can name users beyond the card-holders (loan-only users); each is visited once, push or not. */
class NotificationSchedulerExtraUsersTest {

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final NotificationProducer pushOnly = mock(NotificationProducer.class);
    private final NotificationProducer feed = mock(NotificationProducer.class);

    @Test
    void loanOnlyUsersWithoutPushAreVisitedByTheFeedProducerOnlyAndNobodyTwice() {
        UUID cardHolder = UUID.randomUUID();
        UUID loanOnly = UUID.randomUUID();
        UUID reachable = UUID.randomUUID();
        when(feed.name()).thenReturn("reminder-feed");
        when(feed.runsWithoutPush()).thenReturn(true);
        when(feed.extraFeedUserIds()).thenReturn(List.of(loanOnly, cardHolder, reachable));
        when(feed.evaluate(any())).thenReturn(new NotificationOutcome(1, 1, 0));
        when(pushOnly.name()).thenReturn("bills");
        when(pushOnly.evaluate(any())).thenReturn(NotificationOutcome.NONE);
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of(new UserNotificationSettings(reachable)));
        when(accounts.findDistinctUserIdsByType(AccountType.credit_card)).thenReturn(List.of(cardHolder));

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, accounts, List.of(pushOnly, feed), true).runTick();

        assertEquals(new NotificationScheduler.Summary(3, 3, 3, 0, 0), summary);
        verify(feed, times(1)).evaluate(reachable);
        verify(feed, times(1)).evaluate(cardHolder);
        verify(feed, times(1)).evaluate(loanOnly);
        verify(pushOnly).evaluate(reachable);
        verify(pushOnly, never()).evaluate(loanOnly);
        verify(pushOnly, never()).evaluate(cardHolder);
    }

    @Test
    void aFailingExtraUserLookupIsCountedAndTheCardHoldersAreStillVisited() {
        UUID cardHolder = UUID.randomUUID();
        when(feed.name()).thenReturn("reminder-feed");
        when(feed.runsWithoutPush()).thenReturn(true);
        when(feed.extraFeedUserIds()).thenThrow(new IllegalStateException("db down"));
        when(feed.evaluate(cardHolder)).thenReturn(new NotificationOutcome(1, 1, 0));
        when(repository.findByPushEnabledTrueAndPushSubscriptionsIsNotNull()).thenReturn(List.of());
        when(accounts.findDistinctUserIdsByType(AccountType.credit_card)).thenReturn(List.of(cardHolder));

        NotificationScheduler.Summary summary = new NotificationScheduler(repository, accounts, List.of(feed), true).runTick();

        assertEquals(new NotificationScheduler.Summary(1, 1, 1, 0, 1), summary);
    }
}
