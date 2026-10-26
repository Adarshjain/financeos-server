package com.financeos.chat.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One persisted "Ask the data" chat. Owned by a user; the newest {@link ChatHistoryService#MAX_CONVERSATIONS} are kept. */
@Entity
@Table(name = "chat_conversation")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class ChatConversation {

    public static final int MAX_TITLE_LENGTH = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(length = MAX_TITLE_LENGTH)
    private String title;

    /** The run (send or retry) allowed to store the next assistant message; older workers are dropped. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "active_run_id", length = 36)
    private UUID activeRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
