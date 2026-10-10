package com.financeos.domain.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's record that their imports of {@code fromInstrumentId} go to {@code toInstrumentId}: written
 * when they change an instrument's identifiers and are moved onto another catalog row
 * ({@link InstrumentRepointService}), read by every import path after it matched an instrument.
 * Kept flat (a chain S→T→U is stored as S→U and T→U), so one lookup resolves it.
 */
@Entity
@Table(name = "user_instrument_repoints",
        uniqueConstraints = @UniqueConstraint(name = "uq_uir_user_from", columnNames = {"user_id", "from_instrument_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UserInstrumentRepoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "from_instrument_id", nullable = false, length = 36)
    private UUID fromInstrumentId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "to_instrument_id", nullable = false, length = 36)
    private UUID toInstrumentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserInstrumentRepoint(UUID userId, UUID fromInstrumentId, UUID toInstrumentId) {
        this.userId = userId;
        this.fromInstrumentId = fromInstrumentId;
        this.toInstrumentId = toInstrumentId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
