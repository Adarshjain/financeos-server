package com.financeos.domain.nps;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** Units of one scheme moved by an {@link NpsEntry}; signed (units out are negative). */
@Entity
@Table(name = "nps_entry_line")
@Getter
@Setter
@NoArgsConstructor
public class NpsEntryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private NpsEntry entry;

    @Column(name = "scheme_code", nullable = false, length = 12)
    private String schemeCode;

    @Column(nullable = false, precision = 16, scale = 4)
    private BigDecimal units;

    @Column(precision = 12, scale = 4)
    private BigDecimal nav;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    public NpsEntryLine(String schemeCode, BigDecimal units, BigDecimal nav, BigDecimal amount) {
        this.schemeCode = schemeCode;
        this.units = units;
        this.nav = nav;
        this.amount = amount;
    }
}
