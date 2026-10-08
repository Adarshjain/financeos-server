package com.financeos.domain.notification;

/**
 * What one producer did for one user in one pass: subjects looked at, markers written, devices
 * that accepted a push. Summed across producers by the scheduler.
 */
public record NotificationOutcome(int evaluated, int recorded, int sent) {

    public static final NotificationOutcome NONE = new NotificationOutcome(0, 0, 0);

    public NotificationOutcome plus(NotificationOutcome other) {
        return new NotificationOutcome(evaluated + other.evaluated, recorded + other.recorded, sent + other.sent);
    }
}
