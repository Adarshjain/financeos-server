package com.financeos.gmail.domain;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gmail_processed_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gmail_proc", columnNames = {"connection_id", "gmail_message_id"})
})
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class GmailProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private GmailConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private GmailProcessedStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Transaction transaction;

    @Column(length = 2000)
    private String error;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
