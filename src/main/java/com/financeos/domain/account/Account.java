package com.financeos.domain.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import com.financeos.domain.reward.RewardType;
import com.financeos.domain.user.User;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(name = "exclude_from_net_asset", nullable = false)
    private Boolean excludeFromNetAsset = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "financial_position")
    private FinancialPosition financialPosition;

    @Column
    private String description;

    @Column(name = "ingest_from_date")
    private LocalDate ingestFromDate;

    @Column(name = "last_statement_date")
    private LocalDate lastStatementDate;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    /** Anchor for ANNIVERSARY_YEAR reward windows (card membership anniversary). */
    @Column(name = "reward_anniversary_date")
    private LocalDate rewardAnniversaryDate;

    /** Default currency new reward rules pay in (each rule can override it). */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_reward_type", nullable = false, length = 20)
    private RewardType defaultRewardType = RewardType.CASH;

    /** Value of 1 reward point in INR for cross-card recommendation scoring. */
    @Column(name = "point_value_inr", precision = 12, scale = 4)
    private BigDecimal pointValueInr;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account replacesAccount;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private AccountBankDetails bankDetails;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private AccountCreditCardDetails creditCardDetails;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private AccountBrokerDetails brokerDetails;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("role ASC, createdAt ASC")
    private java.util.List<com.financeos.domain.account.card.Cardholder> cardholders = new java.util.ArrayList<>();

    public java.util.Optional<com.financeos.domain.account.card.Cardholder> primaryCardholder() {
        if (cardholders == null) return java.util.Optional.empty();
        return cardholders.stream().filter(com.financeos.domain.account.card.Cardholder::isPrimary).findFirst();
    }

    public java.util.Optional<com.financeos.domain.account.card.Card> primaryCard() {
        return primaryCardholder().flatMap(com.financeos.domain.account.card.Cardholder::openCard);
    }

    public String primaryLast4() {
        return primaryCard().map(com.financeos.domain.account.card.Card::getLast4).orElse(null);
    }

    @Transient
    private java.math.BigDecimal calculatedBalance;

    @Transient
    private Boolean balanceAnchored;

    @Transient
    private java.math.BigDecimal reconciliationGap;

    @Transient
    private LocalDate anchorDate;

    public boolean isClosed() {
        return isClosed(LocalDate.now());
    }

    public boolean isClosed(LocalDate asOf) {
        return closedOn != null && !asOf.isBefore(closedOn);
    }

    public boolean isClosing() {
        return isClosing(LocalDate.now());
    }

    public boolean isClosing(LocalDate asOf) {
        return closedOn != null && closedOn.isAfter(asOf);
    }

    public AccountStatus getStatus() {
        return getStatus(LocalDate.now());
    }

    public AccountStatus getStatus(LocalDate asOf) {
        if (closedOn == null) {
            return AccountStatus.ACTIVE;
        }
        return closedOn.isAfter(asOf) ? AccountStatus.CLOSING : AccountStatus.CLOSED;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Account(String name, AccountType type) {
        this.name = name;
        this.type = type;
        this.excludeFromNetAsset = false;
    }
}
