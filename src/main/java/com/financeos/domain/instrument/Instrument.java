package com.financeos.domain.instrument;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "instruments")
@Getter
@Setter
@NoArgsConstructor
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstrumentType type;

    @Column(nullable = false)
    private String name;

    @Column
    private String symbol;

    @Column
    private String exchange;

    @Column
    private String isin;

    @Column(name = "amfi_code")
    private String amfiCode;

    @Column(name = "yahoo_symbol")
    private String yahooSymbol;

    @Column(name = "underlying_symbol")
    private String underlyingSymbol;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "underlying_instrument_id", length = 36)
    private UUID underlyingInstrumentId;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type")
    private OptionType optionType;

    @Column(name = "strike_price", precision = 19, scale = 4)
    private BigDecimal strikePrice;

    @Column(name = "lot_size")
    private Integer lotSize;

    @Column(name = "trading_symbol")
    private String tradingSymbol;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
