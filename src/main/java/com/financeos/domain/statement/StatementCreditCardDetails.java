package com.financeos.domain.statement;

import com.financeos.domain.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "statement_credit_card_details")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class StatementCreditCardDetails {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "statement_id", length = 36)
    private UUID statementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @OneToOne
    @MapsId
    @JoinColumn(name = "statement_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Statement statement;

    @Column(name = "total_amount_due", precision = 19, scale = 4)
    private BigDecimal totalAmountDue;

    @Column(name = "minimum_amount_due", precision = 19, scale = 4)
    private BigDecimal minimumAmountDue;

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    @Column(name = "credit_limit", precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "available_credit_limit", precision = 19, scale = 4)
    private BigDecimal availableCreditLimit;

    @Column(name = "finance_charges", precision = 19, scale = 4)
    private BigDecimal financeCharges;

    @Column(name = "fees_and_charges", precision = 19, scale = 4)
    private BigDecimal feesAndCharges;

    @Column(name = "previous_balance", precision = 19, scale = 4)
    private BigDecimal previousBalance;

    @Column(name = "payments_received", precision = 19, scale = 4)
    private BigDecimal paymentsReceived;

    @Column(name = "total_purchases", precision = 19, scale = 4)
    private BigDecimal totalPurchases;

    @Column(name = "reward_points_balance", precision = 19, scale = 4)
    private BigDecimal rewardPointsBalance;

    @Column(name = "reward_points_earned", precision = 19, scale = 4)
    private BigDecimal rewardPointsEarned;

    public StatementCreditCardDetails(Statement statement) {
        this.statement = statement;
        this.statementId = statement.getId();
    }
}
