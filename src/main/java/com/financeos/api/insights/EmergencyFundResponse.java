package com.financeos.api.insights;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How many months the liquid balance covers at the usual monthly outflow.
 *
 * @param liquidBalance  the liquid accounts' balances summed (net worth's bank + wallet/cash rows)
 * @param accounts       the liquid accounts: bank and wallet/cash accounts that are open and not
 *                       excluded from net assets; their ids build the outflow drill
 * @param months         the last six full calendar months, oldest first, each with its outflow and
 *                       whether it falls before the user's history began (always all six)
 * @param historyMonths  how many of the six months are in history (not {@code beforeHistory}): the
 *                       months the median runs over; 0 when the liquid accounts have no transactions
 *                       or their first transaction is in the current month
 * @param medianOutflow  the median of the in-history months' outflows (a quiet month inside history
 *                       counts as zero); zero when {@code historyMonths} is 0
 * @param monthsCovered  liquid balance ÷ median outflow (1 decimal); null when no month is in
 *                       history or the median is not positive
 * @param band           low (&lt; 3 months), medium (3–6) or high (≥ 6); null when monthsCovered is
 */
public record EmergencyFundResponse(
        BigDecimal liquidBalance,
        List<LiquidAccount> accounts,
        List<MonthOutflow> months,
        int historyMonths,
        BigDecimal medianOutflow,
        @Nullable BigDecimal monthsCovered,
        @Nullable String band) {

    /** A liquid account at its calculated balance; {@code type} is bank_account or generic. */
    public record LiquidAccount(UUID id, String name, String type, BigDecimal balance) {
    }

    /**
     * One month's outflow; {@code month} is YYYY-MM. {@code beforeHistory} is true when the month
     * ends before the month of the earliest transaction on any liquid account: its outflow is zero
     * for want of data, not spending, so it is left out of the median.
     */
    public record MonthOutflow(String month, BigDecimal outflow, boolean beforeHistory) {
    }
}
