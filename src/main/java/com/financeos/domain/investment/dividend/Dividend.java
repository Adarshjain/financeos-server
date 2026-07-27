package com.financeos.domain.investment.dividend;

import com.financeos.domain.holding.Holding;
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
@Table(name = "dividends")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Dividend {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Holding holding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DividendType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "per_unit", precision = 19, scale = 4)
    private BigDecimal perUnit;

    @Column(precision = 19, scale = 4)
    private BigDecimal tds;

    @Column(name = "ex_date")
    private LocalDate exDate;

    @Column(name = "pay_date", nullable = false)
    private LocalDate payDate;

    @Column
    private String source = "manual";

    @Column
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
