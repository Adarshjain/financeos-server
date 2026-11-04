package com.financeos.domain.account;

import com.financeos.core.time.AppTime;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * "Close this account?": suggested for an account that is still open, holds exactly zero, was created at least
 * {@value #QUIET_DAYS} days ago, has had no transaction for {@value #QUIET_DAYS} days and was not answered "Not yet"
 * within the last {@value #QUIET_DAYS} days. Needs the balance and the last transaction date already populated on
 * the account. Brokers are never suggested (their balance is a market value and they carry no transaction dates).
 * A card with an open bill owes money, so its balance is not zero: the zero-balance rule covers it.
 */
public final class AccountClosePrompt {

    public static final int QUIET_DAYS = 90;

    private AccountClosePrompt() {
    }

    public static boolean suggest(Account account, LocalDate today) {
        if (account.getClosedOn() != null || account.getType() == AccountType.broker) {
            return false;
        }
        BigDecimal balance = account.getCalculatedBalance();
        if (balance == null || balance.signum() != 0) {
            return false;
        }
        LocalDate cutoff = today.minusDays(QUIET_DAYS);
        if (account.getCreatedAt() == null || LocalDate.ofInstant(account.getCreatedAt(), AppTime.zone()).isAfter(cutoff)) {
            return false;
        }
        LocalDate last = account.getLastTransactionDate();
        if (last != null && last.isAfter(cutoff)) {
            return false;
        }
        if (account.getClosePromptDismissedAt() != null
                && LocalDate.ofInstant(account.getClosePromptDismissedAt(), AppTime.zone()).isAfter(cutoff)) {
            return false;
        }
        return true;
    }
}
