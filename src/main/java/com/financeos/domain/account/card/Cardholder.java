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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "cardholders")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Cardholder {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CardholderRole role = CardholderRole.PRIMARY;

    @Column(name = "person_name", length = 200)
    private String personName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardholderRelationship relationship = CardholderRelationship.SELF;

    @Column(name = "spend_limit", precision = 19, scale = 4)
    private BigDecimal spendLimit;

    @Column(name = "opened_on")
    private LocalDate openedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @OneToMany(mappedBy = "cardholder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("issuedOn DESC, createdAt DESC")
    private List<Card> cards = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isPrimary() {
        return role == CardholderRole.PRIMARY;
    }

    public boolean isAddon() {
        return role == CardholderRole.ADDON;
    }

    public Optional<Card> openCard() {
        if (cards == null) return Optional.empty();
        return cards.stream().filter(Card::isOpen).findFirst();
    }

    public String currentLast4() {
        return openCard().map(Card::getLast4).orElse(null);
    }

    public LocalDate effectiveClosedOn() {
        LocalDate accountClosedOn = account != null ? account.getClosedOn() : null;
        if (accountClosedOn != null && closedOn != null) {
            return accountClosedOn.isBefore(closedOn) ? accountClosedOn : closedOn;
        }
        return accountClosedOn != null ? accountClosedOn : closedOn;
    }

    public boolean isEffectivelyClosed() {
        LocalDate eff = effectiveClosedOn();
        return eff != null && !eff.isAfter(LocalDate.now());
    }

    public boolean isEffectivelyClosed(LocalDate asOf) {
        LocalDate ref = asOf != null ? asOf : LocalDate.now();
        LocalDate eff = effectiveClosedOn();
        return eff != null && !eff.isAfter(ref);
    }

    public String getDisplayName() {
        if (personName != null && !personName.isBlank()) {
            return personName;
        }
        return isPrimary() ? "You" : "Add-on";
    }
}
