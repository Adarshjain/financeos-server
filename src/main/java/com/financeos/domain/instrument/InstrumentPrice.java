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
@Table(name = "instrument_prices")
@Getter
@Setter
@NoArgsConstructor
public class InstrumentPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(name = "close", nullable = false, precision = 19, scale = 4)
    private BigDecimal close;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceSource source;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public InstrumentPrice(Instrument instrument, LocalDate asOf, BigDecimal close, PriceSource source) {
        this.instrument = instrument;
        this.asOf = asOf;
        this.close = close;
        this.source = source;
    }
}
