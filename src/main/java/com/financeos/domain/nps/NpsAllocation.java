package com.financeos.domain.nps;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One row of an account's scheme allocation for a tier: the share of each contribution that goes to a scheme. */
@Entity
@Table(name = "nps_allocation")
@Getter
@Setter
@NoArgsConstructor
public class NpsAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private NpsAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private NpsTier tier;

    @Column(name = "scheme_code", nullable = false, length = 12)
    private String schemeCode;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NpsAllocationSource source = NpsAllocationSource.MANUAL;
}
