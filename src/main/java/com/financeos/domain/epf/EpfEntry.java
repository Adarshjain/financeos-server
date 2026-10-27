package com.financeos.domain.epf;

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

/** One row of an employer's EPF ledger. See {@link EpfLedger} for how rows move the balance. */
@Entity
@Table(name = "epf_entry", indexes = @Index(name = "uq_epf_entry_txn", columnList = "transaction_id", unique = true))
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class EpfEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employer_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private EpfEmployer employer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EpfEntryType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_kind", length = 20)
    private EpfWithdrawalKind withdrawalKind;

    /** First day of the wage month a contribution is for. */
    @Column(name = "wage_month")
    private LocalDate wageMonth;

    @Column(name = "credited_on", nullable = false)
    private LocalDate creditedOn;

    /** Start year of the financial year an interest credit is for. */
    @Column(name = "interest_fy")
    private Integer interestFy;

    @Column(name = "employee_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal employeeAmount = BigDecimal.ZERO;

    @Column(name = "employer_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal employerAmount = BigDecimal.ZERO;

    @Column(name = "pension_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal pensionAmount = BigDecimal.ZERO;

    @Column(name = "tds_amount", precision = 19, scale = 4)
    private BigDecimal tdsAmount;

    /** The other half of a transfer pair (no FK; both rows are removed together). */
    @Column(name = "counterpart_entry_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID counterpartEntryId;

    @Column(length = 500)
    private String note;

    @Column(length = 300)
    private String particulars;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EpfEntrySource source = EpfEntrySource.MANUAL;

    @Column(name = "imported_at")
    private Instant importedAt;

    /** The bank credit a withdrawal landed as ({@code ON DELETE SET NULL}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Transaction transaction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
