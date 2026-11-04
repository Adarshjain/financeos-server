package com.financeos.domain.notification.reminder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.financeos.domain.notification.feed.NotificationFeedService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderResolveListenerTest {

    @Test
    void theCommittedEventResolvesTheUsersRowByKey() {
        NotificationFeedService feed = mock(NotificationFeedService.class);
        UUID user = UUID.randomUUID();
        UUID loan = UUID.randomUUID();

        new ReminderResolveListener(feed).onResolve(new ReminderResolveEvent(user, ReminderKeys.emi(loan, 4)));

        verify(feed).resolve(user, "emi:" + loan + ":4");
    }
}
