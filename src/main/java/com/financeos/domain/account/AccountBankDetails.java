package com.financeos.domain.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account_bank_details")
@Getter
@Setter
@NoArgsConstructor
public class AccountBankDetails {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

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

