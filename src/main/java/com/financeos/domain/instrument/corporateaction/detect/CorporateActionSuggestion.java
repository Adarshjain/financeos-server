package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A corporate action the detector found for one user's holding: either applied automatically (linked to the
 * action it created) or held for the user to decide. One row per (user, instrument, kind, ex-date), so a
 * dismissed suggestion is never raised again.
 */
@Entity
@Table(name = "corporate_action_suggestions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class CorporateActionSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionKind kind;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Column(name = "ratio_from")
    private Integer ratioFrom;

    @Column(name = "ratio_to")
    private Integer ratioTo;

    @Column(length = 500)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SuggestionReason reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corporate_action_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CorporateAction corporateAction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
