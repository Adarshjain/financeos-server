package com.financeos.core.stamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per user: bumped (version + 1) after every committed change to that user's data, on any JVM.
 * Only {@link UserChangeStamps} writes it (plain SQL, so the change listener never sees it); this mapping
 * exists so the test schema gets the table.
 */
@Entity
@Table(name = "user_change_stamp")
public class UserChangeStamp {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "version", nullable = false)
    private long version;

    protected UserChangeStamp() {
    }
}
