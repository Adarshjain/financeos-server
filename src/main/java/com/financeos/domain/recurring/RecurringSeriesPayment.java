package com.financeos.domain.recurring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.UUID;

/** One transaction that belongs to a series; a transaction belongs to at most one. */
@Entity
@Table(name = "recurring_series_payments",
        uniqueConstraints = @UniqueConstraint(name = "uq_recurring_pay_txn", columnNames = "transaction_id"))
@Getter
@Setter
@NoArgsConstructor
public class RecurringSeriesPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "series_id", nullable = false, length = 36)
    private UUID seriesId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_id", nullable = false, length = 36)
    private UUID transactionId;

    public RecurringSeriesPayment(UUID seriesId, UUID transactionId) {
        this.seriesId = seriesId;
        this.transactionId = transactionId;
    }
}
