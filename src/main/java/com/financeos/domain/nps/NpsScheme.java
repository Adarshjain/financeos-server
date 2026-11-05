package com.financeos.domain.nps;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One NPS scheme of the NPS Trust file: shared catalog row, linked one-to-one to its {@code nps_scheme} instrument. */
@Entity
@Table(name = "nps_scheme")
@Getter
@Setter
@NoArgsConstructor
public class NpsScheme {

    /** The NPS Trust scheme id, e.g. SM008018. */
    @Id
    @Column(name = "scheme_code", length = 12)
    private String schemeCode;

    @Column(name = "instrument_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID instrumentId;

    @Column(name = "pfm_code", length = 8)
    private String pfmCode;

    @Column(name = "pfm_name", length = 200)
    private String pfmName;

    /** E, C, G or A; null for schemes with no single class (UPS, Lite, APY, Vatsalya, ...). */
    @Column(name = "asset_class", length = 1)
    private String assetClass;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private NpsSchemeTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsVariant variant = NpsVariant.NONE;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
