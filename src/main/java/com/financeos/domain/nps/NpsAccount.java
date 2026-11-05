package com.financeos.domain.nps;

import com.financeos.core.crypto.EncryptedStringConverter;
import com.financeos.domain.epf.EpfEmployer;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One NPS account (a PRAN) of the user. The PRAN is stored encrypted; only its last four digits are readable. */
@Entity
@Table(name = "nps_account")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class NpsAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pran_encrypted", nullable = false, length = 500)
    private String pran;

    @Column(name = "pran_last4", nullable = false, length = 4)
    private String pranLast4;

    @Column(name = "holder_name", nullable = false, length = 200)
    private String holderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsRelation relation = NpsRelation.SELF;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private NpsSector sector = NpsSector.ALL_CITIZEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NpsCra cra = NpsCra.PROTEAN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employer_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private EpfEmployer employer;

    @Column(name = "tier2_active", nullable = false)
    private boolean tier2Active;

    @Column(name = "scheme_choice", length = 80)
    private String schemeChoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier1_status", length = 20)
    private NpsTier1Status tier1Status;

    @Column(name = "tier1_status_as_of")
    private java.time.LocalDate tier1StatusAsOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsAccountStatus status = NpsAccountStatus.ACTIVE;

    @Column(name = "read_gmail", nullable = false)
    private boolean readGmail = true;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "statement_password_encrypted", length = 500)
    private String statementPassword;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
