package com.financeos.domain.investment;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
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
@Table(name = "trade_settlement_classifications")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class TradeSettlementClassification {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Holding holding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "intraday_qty", nullable = false, precision = 19, scale = 8)
    private BigDecimal intradayQty;

    @Column(name = "intraday_buy_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal intradayBuyValue;

    @Column(name = "intraday_sell_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal intradaySellValue;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public TradeSettlementClassification(
            User user,
            Account brokerAccount,
            Holding holding,
            Instrument instrument,
            LocalDate tradeDate,
            BigDecimal intradayQty,
            BigDecimal intradayBuyValue,
            BigDecimal intradaySellValue
    ) {
        this.user = user;
        this.brokerAccount = brokerAccount;
        this.holding = holding;
        this.instrument = instrument;
        this.tradeDate = tradeDate;
        this.intradayQty = intradayQty;
        this.intradayBuyValue = intradayBuyValue;
        this.intradaySellValue = intradaySellValue;
        this.createdAt = Instant.now();
    }
}
