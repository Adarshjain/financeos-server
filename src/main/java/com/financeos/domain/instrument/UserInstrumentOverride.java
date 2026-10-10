package com.financeos.domain.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's own view of one instrument: any subset of the display fields (name, symbol, exchange,
 * currency, type) and the asset class, each pinned over the shared catalog value for that user only
 * (null = the catalog value applies). The instrument master is shared; this row is not. A row with
 * every field null is deleted rather than kept.
 */
@Entity
@Table(name = "user_instrument_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uq_uio_user_instrument", columnNames = {"user_id", "instrument_id"}))
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class UserInstrumentOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "instrument_id", nullable = false, length = 36)
    private UUID instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", length = 20)
    private AssetClass assetClass;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "symbol", length = 50)
    private String symbol;

    @Column(name = "exchange", length = 20)
    private String exchange;

    @Column(name = "currency", length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", length = 50)
    private InstrumentType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserInstrumentOverride(UUID userId, UUID instrumentId, AssetClass assetClass) {
        this.userId = userId;
        this.instrumentId = instrumentId;
        this.assetClass = assetClass;
    }

    public UserInstrumentOverride(UUID userId, UUID instrumentId) {
        this.userId = userId;
        this.instrumentId = instrumentId;
    }

    /** Whether no field is overridden any more (the row should then be deleted). */
    public boolean isEmpty() {
        return assetClass == null && name == null && symbol == null && exchange == null && currency == null
                && type == null;
    }

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
