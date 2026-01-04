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
@Table(name = "account_mutual_fund_details")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountMutualFundDetails {

    @Id
    private UUID accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToOne
    @MapsId
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "instrument_code", nullable = false)
    private String instrumentCode;

    @Column(name = "last_traded_price", precision = 19, scale = 4)
    private BigDecimal lastTradedPrice;

    public AccountMutualFundDetails(Account account, String instrumentCode, BigDecimal lastTradedPrice) {
        this.account = account;
        this.accountId = account.getId();
        this.instrumentCode = instrumentCode;
        this.lastTradedPrice = lastTradedPrice;
    }
}
