package com.financeos.domain.nps.link;

import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * "Not this one" ({@code entry} set: this payment is not that contribution) or "not NPS" ({@code entry} null: this
 * payment is not an NPS contribution at all).
 */
@Entity
@Table(name = "nps_link_dismissal",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "transaction_id", "entry_id"}))
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class NpsLinkDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NpsEntry entry;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
