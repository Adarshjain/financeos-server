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
@Table(name = "instrument_aliases")
@Getter
@Setter
@NoArgsConstructor
public class InstrumentAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Column(name = "old_symbol", length = 50)
    private String oldSymbol;

    @Column(name = "old_name", length = 255)
    private String oldName;

    @Column(length = 50)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public InstrumentAlias(Instrument instrument, String oldSymbol, String oldName, String source) {
        this.instrument = instrument;
        this.oldSymbol = oldSymbol;
        this.oldName = oldName;
        this.source = source;
    }
}
