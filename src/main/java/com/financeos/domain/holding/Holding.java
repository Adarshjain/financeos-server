package com.financeos.domain.holding;

import com.financeos.domain.account.Account;
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
import java.util.UUID;

@Entity
@Table(name = "holdings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account brokerAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Column
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Holding(Account brokerAccount, Instrument instrument, String notes) {
        this.brokerAccount = brokerAccount;
        this.instrument = instrument;
        this.notes = notes;
    }
}
