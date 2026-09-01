package com.financeos.domain.account;

import com.financeos.core.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account_credit_card_details")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountCreditCardDetails {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "payment_due_day", nullable = false)
    private Integer paymentDueDay;

    @Column(name = "grace_period_days", nullable = false)
    private Integer gracePeriodDays;

    @Column(name = "issuer", length = 100)
    private String issuer;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "statement_password")
    private String statementPassword;

    public AccountCreditCardDetails(Account account, BigDecimal creditLimit,
            Integer paymentDueDay, Integer gracePeriodDays, String statementPassword) {
        this.account = account;
        this.accountId = account.getId();
        this.creditLimit = creditLimit;
        this.paymentDueDay = paymentDueDay;
        this.gracePeriodDays = gracePeriodDays;
        this.statementPassword = statementPassword;
    }

    public AccountCreditCardDetails(Account account, BigDecimal creditLimit,
            Integer paymentDueDay, Integer gracePeriodDays, String statementPassword,
            String issuer, String productName) {
        this(account, creditLimit, paymentDueDay, gracePeriodDays, statementPassword);
        this.issuer = issuer;
        this.productName = productName;
    }
}
