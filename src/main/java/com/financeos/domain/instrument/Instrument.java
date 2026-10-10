package com.financeos.domain.instrument;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
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

    @Column(nullable = false)
    private String currency = "INR";

    /** Allocation bucket; null until classified (readers fall back to {@link AssetClassifier#effectiveAssetClass}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", length = 20)
    private AssetClass assetClass;

    /** Mutual funds: the raw AMFI scheme-category header, e.g. "Open Ended Schemes(Equity Scheme - Large Cap Fund)". */
    @Column(name = "scheme_category", length = 200)
    private String schemeCategory;

    /** Where {@link #assetClass} came from: AMFI or RULE (a user's override is per user, in {@link UserInstrumentOverride}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class_source", length = 10)
    private AssetClassSource assetClassSource;

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
