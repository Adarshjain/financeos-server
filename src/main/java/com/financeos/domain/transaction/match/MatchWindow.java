package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many days apart a record and a statement line may be dated and still be the same payment (W): card posting
 * lag makes it 3 days for credit cards, 1 day for bank accounts; foreign charges post later (7 days, wave 3).
 */
@Component
public class MatchWindow {

    public static final int DEFAULT_CARD_DAYS = 3;
    public static final int DEFAULT_BANK_DAYS = 1;
    public static final int DEFAULT_FOREIGN_DAYS = 7;

    private final int cardDays;
    private final int bankDays;
    private final int foreignDays;

    /** The defaults: 3 card, 1 bank, 7 foreign. */
    public MatchWindow() {
        this(DEFAULT_CARD_DAYS, DEFAULT_BANK_DAYS, DEFAULT_FOREIGN_DAYS);
    }

    @Autowired
    public MatchWindow(@Value("${matching.window.card-days:3}") int cardDays,
                       @Value("${matching.window.bank-days:1}") int bankDays,
                       @Value("${matching.window.foreign-days:7}") int foreignDays) {
        this.cardDays = cardDays;
        this.bankDays = bankDays;
        this.foreignDays = foreignDays;
    }

    /** W for the account: the card window for credit cards, the bank window for everything else. */
    public int daysFor(Account account) {
        return account != null && account.getType() == AccountType.credit_card ? cardDays : bankDays;
    }

    /** W for a foreign-currency charge (wave 3). */
    public int foreignDays() {
        return foreignDays;
    }
}
