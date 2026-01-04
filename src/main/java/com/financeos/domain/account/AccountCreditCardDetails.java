package com.financeos.domain.account;

import com.financeos.core.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

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
    private User user;

    @Id
    private UUID accountId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "last4", nullable = false)
    private String last4;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "payment_due_day", nullable = false)
    private Integer paymentDueDay;

    @Column(name = "grace_period_days", nullable = false)
    private Integer gracePeriodDays;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "statement_password")
    private String statementPassword;

    public AccountCreditCardDetails(Account account, String last4, BigDecimal creditLimit,
            Integer paymentDueDay, Integer gracePeriodDays, String statementPassword) {
        this.account = account;
        this.accountId = account.getId();
        this.last4 = last4;
        this.creditLimit = creditLimit;
        this.paymentDueDay = paymentDueDay;
        this.gracePeriodDays = gracePeriodDays;
        this.statementPassword = statementPassword;
    }
}
