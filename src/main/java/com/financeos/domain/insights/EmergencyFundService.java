package com.financeos.domain.insights;

import com.financeos.api.insights.EmergencyFundResponse;
import com.financeos.api.insights.EmergencyFundResponse.LiquidAccount;
import com.financeos.api.insights.EmergencyFundResponse.MonthOutflow;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The emergency fund: how many months the liquid balance would cover at the usual monthly outflow.
 *
 * <ul>
 *   <li>Liquid accounts: bank and wallet/cash ({@code generic}) accounts that count towards net
 *       worth today (open, not excluded from net assets) — exactly net worth's {@code kind in
 *       (bank_account, generic)} rows, so the balance drills into net worth.</li>
 *   <li>Monthly outflow: debits on the liquid accounts that are not excluded, leaving out
 *       transactions in a TRANSFER or REVERSAL link (money moved between own accounts, reversals).
 *       A CC_PAYMENT link stays in: card bills are outflow, as are EMIs. It is the transactions
 *       datasource's {@code spend} sum over {@code account in <ids>, type is DEBIT, isExcluded is
 *       false, linkType not_in (TRANSFER, REVERSAL)} for the month, so each month drills into that
 *       ad-hoc KPI.</li>
 *   <li>History starts with the first full month of data: the month of the earliest transaction
 *       (any type, excluded or not) on a liquid account when that transaction is on day 1–{@value
 *       #FULL_MONTH_LAST_START_DAY} of it, else the month after (a month joined mid-way would show a
 *       partial outflow). Of the last {@value #MONTHS} full calendar months, those before it are still
 *       returned but flagged {@code beforeHistory} and left out of the median, so a new user is not
 *       judged on months they had no data for. Inside history a month with no outflow counts as
 *       zero.</li>
 *   <li>The median of the in-history months; months covered = liquid ÷ median (null when no month
 *       is in history or the median is not positive), banded low &lt; 3, medium 3–6, high ≥ 6.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class EmergencyFundService {

    public static final int MONTHS = 6;
    /** A first transaction on or before this day of its month still makes that month a full one. */
    public static final int FULL_MONTH_LAST_START_DAY = 3;
    public static final List<String> EXCLUDED_LINK_TYPES = List.of("TRANSFER", "REVERSAL");
    public static final String BAND_LOW = "low";
    public static final String BAND_MEDIUM = "medium";
    public static final String BAND_HIGH = "high";

    // Link type as the transactions datasource reads it (one link per transaction; MIN keeps it 1:1).
    private static final String OUTFLOW_SQL = "SELECT t.transaction_date AS d, SUM(t.amount) AS total"
            + " FROM transactions t"
            + " LEFT JOIN (SELECT m.transaction_id, MIN(l.type) AS link_type"
            + " FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id"
            + " GROUP BY m.transaction_id) lk ON lk.transaction_id = t.id"
            + " WHERE t.user_id = :userId AND t.account_id IN (:accountIds)"
            + " AND t.type = 'DEBIT' AND t.is_excluded = :notExcluded"
            + " AND (lk.link_type IS NULL OR lk.link_type NOT IN (:excludedLinks))"
            + " AND t.transaction_date BETWEEN :fromDate AND :toDate"
            + " GROUP BY t.transaction_date";

    // Where the user's history on the liquid accounts begins; every transaction counts.
    private static final String FIRST_TRANSACTION_SQL = "SELECT MIN(t.transaction_date) AS first_day"
            + " FROM transactions t WHERE t.user_id = :userId AND t.account_id IN (:accountIds)";

    private final AccountService accountService;
    private final NamedParameterJdbcTemplate jdbc;

    public EmergencyFundService(AccountService accountService, NamedParameterJdbcTemplate jdbc) {
        this.accountService = accountService;
        this.jdbc = jdbc;
    }

    public EmergencyFundResponse emergencyFund() {
        UUID userId = UserContext.getCurrentUserId();
        LocalDate today = AppTime.today();

        List<LiquidAccount> accounts = new ArrayList<>();
        BigDecimal liquid = BigDecimal.ZERO;
        for (Account account : accountService.getAllAccounts()) {
            if (!isLiquid(account, today)) {
                continue;
            }
            BigDecimal balance = NetWorthPlacement.balance(account);
            accounts.add(new LiquidAccount(account.getId(), account.getName(), NetWorthPlacement.kind(account), balance));
            liquid = liquid.add(balance);
        }

        YearMonth current = YearMonth.from(today);
        Map<YearMonth, BigDecimal> outflows = new LinkedHashMap<>();
        for (int i = MONTHS; i >= 1; i--) {
            outflows.put(current.minusMonths(i), BigDecimal.ZERO);
        }
        YearMonth historyStart = null;
        if (!accounts.isEmpty() && userId != null) {
            List<String> accountIds = accounts.stream().map(a -> a.id().toString()).toList();
            MapSqlParameterSource first = new MapSqlParameterSource()
                    .addValue("userId", userId.toString())
                    .addValue("accountIds", accountIds);
            Date firstDay = jdbc.queryForObject(FIRST_TRANSACTION_SQL, first, (rs, n) -> rs.getDate("first_day"));
            historyStart = firstDay == null ? null : historyStart(firstDay.toLocalDate());

            LocalDate from = current.minusMonths(MONTHS).atDay(1);
            LocalDate to = current.minusMonths(1).atEndOfMonth();
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", userId.toString())
                    .addValue("accountIds", accountIds)
                    .addValue("excludedLinks", EXCLUDED_LINK_TYPES)
                    .addValue("notExcluded", 0)
                    .addValue("fromDate", Date.valueOf(from))
                    .addValue("toDate", Date.valueOf(to));
            jdbc.query(OUTFLOW_SQL, params, rs -> {
                LocalDate day = rs.getDate("d").toLocalDate();
                BigDecimal total = rs.getBigDecimal("total");
                if (total != null) {
                    outflows.merge(YearMonth.from(day), total, BigDecimal::add);
                }
            });
        }

        List<MonthOutflow> months = months(outflows, historyStart);
        List<BigDecimal> inHistory = months.stream().filter(m -> !m.beforeHistory()).map(MonthOutflow::outflow).toList();
        BigDecimal median = median(inHistory);
        BigDecimal covered = inHistory.isEmpty() ? null : monthsCovered(liquid, median);
        return new EmergencyFundResponse(liquid, accounts, months, inHistory.size(), median, covered, band(covered));
    }

    /**
     * The first month whose outflow is complete: the first transaction's month when it falls on day
     * 1–{@value #FULL_MONTH_LAST_START_DAY}, else the next month.
     */
    static YearMonth historyStart(LocalDate firstTransaction) {
        YearMonth month = YearMonth.from(firstTransaction);
        return firstTransaction.getDayOfMonth() <= FULL_MONTH_LAST_START_DAY ? month : month.plusMonths(1);
    }

    /**
     * The months in order, each flagged {@code beforeHistory} when it is earlier than the month of the
     * first transaction ({@code historyStart}); with no transactions at all every month is before
     * history.
     */
    static List<MonthOutflow> months(Map<YearMonth, BigDecimal> outflows, YearMonth historyStart) {
        return outflows.entrySet().stream()
                .map(e -> new MonthOutflow(e.getKey().toString(), e.getValue(),
                        historyStart == null || e.getKey().isBefore(historyStart)))
                .toList();
    }

    /** Bank or wallet/cash, counted in net worth today (open and not excluded from net assets). */
    static boolean isLiquid(Account account, LocalDate today) {
        String kind = NetWorthPlacement.kind(account);
        boolean liquidKind = AccountType.bank_account.name().equals(kind) || AccountType.generic.name().equals(kind);
        return liquidKind && NetWorthPlacement.omission(account, today) == null;
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /** Liquid ÷ median to one decimal; null when the median is not positive. */
    static BigDecimal monthsCovered(BigDecimal liquid, BigDecimal median) {
        if (median == null || median.signum() <= 0) {
            return null;
        }
        return liquid.divide(median, 1, RoundingMode.HALF_UP);
    }

    static String band(BigDecimal monthsCovered) {
        if (monthsCovered == null) {
            return null;
        }
        if (monthsCovered.compareTo(BigDecimal.valueOf(3)) < 0) {
            return BAND_LOW;
        }
        return monthsCovered.compareTo(BigDecimal.valueOf(6)) < 0 ? BAND_MEDIUM : BAND_HIGH;
    }
}
