package com.financeos.domain.investment.fno;

import com.financeos.domain.account.Account;
import com.financeos.domain.instrument.OptionType;
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
@Table(name = "fno_trades")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class FnoTrade {

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
    @JoinColumn(name = "broker_account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account brokerAccount;

    @Column(name = "trading_symbol", nullable = false, length = 100)
    private String tradingSymbol;

    @Column(name = "underlying_symbol", length = 50)
    private String underlyingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 10)
    private FnoContractType contractType;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", length = 10)
    private OptionType optionType;

    @Column(name = "strike_price", precision = 19, scale = 4)
    private BigDecimal strikePrice;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(name = "buy_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal buyValue;

    @Column(name = "sell_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal sellValue;

    @Column(name = "total_charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCharges = BigDecimal.ZERO;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(nullable = false, length = 20)
    private String source = "manual";

    @Column(name = "external_ref", length = 200)
    private String externalRef;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
