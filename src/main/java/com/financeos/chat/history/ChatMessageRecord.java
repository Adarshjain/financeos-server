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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One stored message. Always reached through its (user-checked) conversation. */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageRecord {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "conversation_id", nullable = false, length = 36)
    private UUID conversationId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 10)
    private String role;

    /** USER: the question text. ASSISTANT: the JSON answer payload. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "draft_result")
    private String draftResult;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
