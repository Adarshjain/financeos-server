package com.financeos.domain.investment.reconcile;

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

/** One row of the latest demat holdings file the user uploaded for a broker account: what the broker says was held. */
@Entity
@Table(name = "broker_holding_snapshots")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class BrokerHoldingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "broker_account_id", nullable = false, length = 36)
    private UUID brokerAccountId;

    @Column(length = 20)
    private String isin;

    @Column(length = 60)
    private String symbol;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
