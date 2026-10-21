package com.financeos.domain.notification;

import java.util.UUID;

/**
 * One source of notifications (card bills, Gmail reconnect, EMIs, …). The hourly
 * {@link NotificationScheduler} calls {@link #evaluate} for every reachable user with
 * {@code UserContext} already set; implementations open their own transaction
 * ({@code REQUIRES_NEW}), keep their idempotency marker on their own rows, and advance that
 * marker even when nothing can be sent (push off, muted, kind disabled) so enabling push later
 * never replays a backlog.
 */
public interface NotificationProducer {

    /** Short stable name for logs. */
    String name();

    NotificationOutcome evaluate(UUID userId);

    /**
     * Whether the tick also runs this producer for users push cannot reach (its output feeds the in-app
     * feed too). Only the card-holders among them are visited; every other producer stays push-only.
     */
    default boolean runsWithoutPush() {
        return false;
    }
}
