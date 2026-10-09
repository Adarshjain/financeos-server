package com.financeos.domain.report.datasource.impl;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Where one item lands in net worth: its side and the non-negative value shown on that side.
 *
 * <p>The single home of the net worth side/value rules, shared by {@link NetWorthDatasource}'s rows,
 * its "not counted" list and the net worth row breakdown, so the three can never disagree. In every
 * case the value is the magnitude of the item's signed amount (an account's balance, a loan's
 * outstanding principal, a counterparty's net position); only the side rules differ.
 *
 * @param side  asset or liability
 * @param value the amount shown on that side, never negative
 */
public record NetWorthPlacement(FinancialPosition side, BigDecimal value) {

    /** Why an account is left out of net worth. */
    public enum Omission {
        /** The account is marked "exclude from net assets". */
        EXCLUDED("excluded"),
        /** The account was closed on or before today. */
        CLOSED("closed");

        private final String reason;

        Omission(String reason) {
            this.reason = reason;
        }

        /** The reason code used by the KPI underlying data ({@code excluded} / {@code closed}). */
        public String reason() {
            return reason;
        }
    }

    /** Positive for an asset, negative for a liability; the row's {@code signedValue}. */
    public BigDecimal signedValue() {
        return side == FinancialPosition.asset ? value : value.negate();
    }

    /**
     * Why the account does not count towards net worth as of {@code today}, or null when it counts.
     * Exclusion wins over closure. An account closed after today is still open and counts.
     */
    public static Omission omission(Account account, LocalDate today) {
        if (Boolean.TRUE.equals(account.getExcludeFromNetAsset())) {
            return Omission.EXCLUDED;
        }
        if (account.getClosedOn() != null && !account.getClosedOn().isAfter(today)) {
            return Omission.CLOSED;
        }
        return null;
    }

    /** The account's calculated balance, a missing one counting as zero. */
    public static BigDecimal balance(Account account) {
        return account.getCalculatedBalance() != null ? account.getCalculatedBalance() : BigDecimal.ZERO;
    }

    /** The account's kind value ({@link AccountType} name; an untyped account is {@code generic}). */
    public static String kind(Account account) {
        return account.getType() != null ? account.getType().name() : AccountType.generic.name();
    }

    /**
     * An account at its calculated balance. Side comes from the account's financial position when
     * set; otherwise a credit card is a liability and everything else an asset. A negative balance
     * flips the side to liability (shown as a positive value); a positive credit card balance is an
     * asset (overpaid), whatever its financial position.
     */
    public static NetWorthPlacement ofAccount(Account account) {
        BigDecimal balance = balance(account);
        FinancialPosition side = account.getFinancialPosition();
        if (side == null) {
            side = account.getType() == AccountType.credit_card ? FinancialPosition.liability : FinancialPosition.asset;
        }
        if (balance.signum() < 0) {
            // Owed money: a card's bill, an overdrawn bank account, a negative generic balance.
            return new NetWorthPlacement(FinancialPosition.liability, balance.negate());
        }
        if (balance.signum() > 0 && account.getType() == AccountType.credit_card) {
            return new NetWorthPlacement(FinancialPosition.asset, balance); // overpaid card
        }
        return new NetWorthPlacement(side, balance);
    }

    /** A loan's outstanding principal (missing counts as zero), always a liability. */
    public static NetWorthPlacement ofLoan(LoanResponse loan) {
        return new NetWorthPlacement(FinancialPosition.liability, outstanding(loan).abs());
    }

    /** A loan's outstanding principal, a missing one counting as zero. */
    public static BigDecimal outstanding(LoanResponse loan) {
        return loan.outstandingPrincipal() != null ? loan.outstandingPrincipal() : BigDecimal.ZERO;
    }

    /**
     * A counterparty's net position: they owe you (asset) or you owe them (liability); null when
     * the position is unknown or settled (zero), which is left out of net worth.
     */
    public static NetWorthPlacement ofLending(CounterpartyResponse counterparty) {
        BigDecimal net = counterparty.netPosition();
        if (net == null || net.signum() == 0) {
            return null;
        }
        return new NetWorthPlacement(net.signum() > 0 ? FinancialPosition.asset : FinancialPosition.liability, net.abs());
    }
}
