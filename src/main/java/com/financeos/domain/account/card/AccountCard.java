package com.financeos.domain.account.card;

import com.financeos.domain.account.Account;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "account_cards")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountCard {

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
    @JoinColumn(name = "account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Column(length = 100)
    private String label;

    @Column(name = "holder_name", length = 200)
    private String holderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardRelationship relationship = CardRelationship.SELF;

    @Column(nullable = false, length = 4)
    private String last4;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "spend_limit", precision = 19, scale = 4)
    private BigDecimal spendLimit;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
