package com.financeos.domain.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account_bank_details")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountBankDetails {

    @Id
    private UUID accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToOne
    @MapsId
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "last4")
    private String last4;

    public AccountBankDetails(Account account, BigDecimal openingBalance, String last4) {
        this.account = account;
        this.accountId = account.getId();
        this.openingBalance = openingBalance;
        this.last4 = last4;
    }
}
