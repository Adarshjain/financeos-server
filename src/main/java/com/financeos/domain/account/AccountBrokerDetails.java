package com.financeos.domain.account;

import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account_broker_details")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountBrokerDetails {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @OneToOne
    @MapsId
    @JoinColumn(name = "account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "cash_balance", precision = 19, scale = 2)
    private BigDecimal cashBalance = BigDecimal.ZERO;

    public AccountBrokerDetails(Account account, String provider, String clientId, BigDecimal cashBalance) {
        this.account = account;
        this.accountId = account.getId();
        this.provider = provider;
        this.clientId = clientId;
        this.cashBalance = cashBalance != null ? cashBalance : BigDecimal.ZERO;
    }
}
