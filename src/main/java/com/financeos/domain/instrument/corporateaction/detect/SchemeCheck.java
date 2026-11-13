package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.Instrument;
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
 * The last holdings check of one user for one demerger or merger of a stock: per broker account whether the user's
 * holdings agree with what the company's notices say (a JSON list), and whether all of them did.
 */
@Entity
@Table(name = "corporate_scheme_checks")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class SchemeCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    /** demerger or merger. */
    @Column(nullable = false, length = 20)
    private String kind;

    /** The demerger's ex-date; null for a merger. */
    @Column(name = "ex_date")
    private LocalDate exDate;

    @Column(nullable = false)
    private boolean agrees;

    @Column(name = "checks_json", length = 4000)
    private String checksJson;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
