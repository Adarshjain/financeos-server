package com.financeos.domain.categorization;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * How far a backfill has got for one user ({@code lastTxnId}: every row up to it has been looked at), or, with
 * {@code userId} = {@link #RUN}, whether the whole run is finished.
 */
@Entity
@Table(name = "category_backfill_progress")
@Getter
@Setter
@NoArgsConstructor
public class CategoryBackfillProgress {

    /** The user_id of the row that marks a whole run as finished. */
    public static final String RUN = "*";

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        @Column(name = "backfill_key", length = 40, nullable = false)
        private String backfillKey;

        @Column(name = "user_id", length = 36, nullable = false)
        private String userId;

        public Key(String backfillKey, String userId) {
            this.backfillKey = backfillKey;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(backfillKey, k.backfillKey) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(backfillKey, userId);
        }
    }

    @EmbeddedId
    private Key id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "last_txn_id", length = 36)
    private UUID lastTxnId;

    @Column(nullable = false)
    private int scanned;

    @Column(nullable = false)
    private int categorised;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CategoryBackfillProgress(String backfillKey, String userId) {
        this.id = new Key(backfillKey, userId);
        this.updatedAt = com.financeos.core.time.AppTime.nowInstant();
    }
}
