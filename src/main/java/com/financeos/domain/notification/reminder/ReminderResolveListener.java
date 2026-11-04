package com.financeos.domain.notification.reminder;

import com.financeos.domain.notification.feed.NotificationFeedService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Clears the feed row of a bill/EMI the moment its payment commits (the daily run catches every other way it gets settled). */
@Component
public class ReminderResolveListener {

    private final NotificationFeedService feedService;

    public ReminderResolveListener(NotificationFeedService feedService) {
        this.feedService = feedService;
    }

    /** Never throws: {@link NotificationFeedService#resolve} swallows its own failures. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResolve(ReminderResolveEvent event) {
        if (event.prefix()) {
            feedService.resolvePrefix(event.userId(), event.dedupeKey());
        } else {
            feedService.resolve(event.userId(), event.dedupeKey());
        }
    }
}
