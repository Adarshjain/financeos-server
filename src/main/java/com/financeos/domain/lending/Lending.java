package com.financeos.domain.lending;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "lendings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Lending {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Counterparty counterparty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LendingDirection direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "lend_date", nullable = false)
    private LocalDate lendDate;

    @Column(name = "expected_return_date")
    private LocalDate expectedReturnDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LendingStatus status = LendingStatus.outstanding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Transaction transaction;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = LendingStatus.outstanding;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
