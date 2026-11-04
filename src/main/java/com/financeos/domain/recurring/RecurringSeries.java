package com.financeos.domain.recurring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A detected (or tracked) run of regular payments to one merchant; one row per user and merchant key. */
@Entity
@Table(name = "recurring_series",
        uniqueConstraints = @UniqueConstraint(name = "uq_recurring_user_merchant", columnNames = {"user_id", "merchant_key"}))
@Getter
@Setter
@NoArgsConstructor
public class RecurringSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "merchant_key", nullable = false, length = 255)
    private String merchantKey;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringPeriod period;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @Column(name = "last_paid_on", nullable = false)
    private LocalDate lastPaidOn;

    @Column(name = "next_due_on", nullable = false)
    private LocalDate nextDueOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringStatus status = RecurringStatus.PROPOSED;

    @Column(name = "missed_cycles", nullable = false)
    private int missedCycles;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "loan_id", length = 36)
    private UUID loanId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
