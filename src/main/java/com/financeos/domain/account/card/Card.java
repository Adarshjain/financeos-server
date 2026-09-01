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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "cards")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Card {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cardholder_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Cardholder cardholder;

    @Column(name = "last4", nullable = false, length = 4)
    private String last4;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

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

    public boolean isOpen() {
        return closedOn == null;
    }

    public boolean isClosed() {
        return closedOn != null;
    }

    public boolean isOpen(LocalDate date) {
        if (date == null) return isOpen();
        boolean afterOrOnIssued = (issuedOn == null || !date.isBefore(issuedOn));
        boolean beforeClosed = (closedOn == null || date.isBefore(closedOn));
        return afterOrOnIssued && beforeClosed;
    }

    public boolean isClosed(LocalDate date) {
        return !isOpen(date);
    }

    public void close(LocalDate on) {
        this.closedOn = on != null ? on : LocalDate.now();
    }
}
