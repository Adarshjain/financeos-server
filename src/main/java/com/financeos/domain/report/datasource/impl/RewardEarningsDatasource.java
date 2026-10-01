package com.financeos.domain.report.datasource.impl;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCalculationService.ReportLine;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;
import com.financeos.domain.transaction.TransactionChannel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.domain.report.datasource.impl.RewardReportSupport.NONE;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_POINTS;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_RUPEES;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.period;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.valueInr;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.zero;

/**
 * Computed report datasource: one row per transaction × rule reward evaluation line.
 *
 * <p>A transaction can produce several lines (one EXCLUSIVE + any ADDITIVE rule), so the
 * per-transaction measures ({@code spend}, {@code amount}, {@code instantDiscount},
 * {@code convenienceFee}, {@code txnCount}) are carried by the transaction's first line
 * only and are zero on the rest: summing them never double-counts. {@code spend},
 * discount, fee and {@code txnCount} also skip transfer/excluded/fully-refunded
 * transactions, matching the Rewards page summary.
 *
 * <p>Caveats:
 * <ul>
 *   <li>Points are valued at the card's {@code pointValueInr}; unvalued points add zero to
 *       the rupee measures and stay visible in {@code points} / {@code earned}.</li>
 *   <li>{@code earned} is mixed units across rows (rupees or points).</li>
 *   <li>{@code category} is multi-valued: grouping by it counts a line under each of its
 *       transaction's categories, so category groups can sum past the total.</li>
 *   <li>Milestone payouts live in {@code reward_milestones}, not here.</li>
 * </ul>
 */
@Component
public class RewardEarningsDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);
    private static final List<Aggregation> SUM_ONLY = List.of(Aggregation.SUM);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    static final String DATE_FIELD = "effectiveDate";
    static final String UNATTRIBUTED = "Unattributed";

    private final RewardCalculationService rewardCalculationService;
    private final RewardReportSupport support;
    private final List<FieldDef> fields;

    public RewardEarningsDatasource(RewardCalculationService rewardCalculationService,
                                    RewardReportSupport support) {
        this.rewardCalculationService = rewardCalculationService;
        this.support = support;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "reward_earnings";
    }

    @Override
    public String label() {
        return "Reward Earnings";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public String cycleAccountKey() {
        return "cardId";
    }

    @Override
    public List<Map<String, Object>> rows() {
        return rows(null);
    }

    /** Narrows each card's evaluation to the hint when it bounds {@code effectiveDate}. */
    @Override
    public List<Map<String, Object>> rows(@Nullable DateHint hint) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return List.of();
        }
        List<Account> accounts = support.ruleAccounts(userId);
        if (accounts.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> ruleLabels = support.ruleLabels(accounts);
        DateHint narrow = hint != null && DATE_FIELD.equals(hint.field()) ? hint : null;

        List<Map<String, Object>> rows = new ArrayList<>();
        int i = 0;
        for (Account account : accounts) {
            RewardReportSupport.DateBounds bounds = support.bounds(account.getId());
            if (bounds == null) {
                continue;
            }
            LocalDate from = bounds.from();
            LocalDate to = bounds.to();
            if (narrow != null) {
                from = narrow.from().isAfter(from) ? narrow.from() : from;
                to = narrow.to().isBefore(to) ? narrow.to() : to;
                if (from.isAfter(to)) {
                    continue;
                }
            }
            List<ReportLine> lines = rewardCalculationService.reportLines(account.getId(), from, to);
            if (lines == null) {
                continue;
            }
            for (ReportLine reportLine : lines) {
                rows.add(row(reportLine, account, ruleLabels, i++));
            }
        }
        return rows;
    }

    private Map<String, Object> row(ReportLine reportLine, Account account, Map<UUID, String> ruleLabels, int index) {
        RewardLineResponse line = reportLine.line();
        BigDecimal pointValue = account.getPointValueInr();
        String unit = line.earnedUnit();
        BigDecimal earned = line.earned() != null ? line.earned() : BigDecimal.ZERO;

        BigDecimal value = valueInr(earned, unit, pointValue);
        BigDecimal cash = UNIT_RUPEES.equals(unit) ? value : zero();
        BigDecimal points = UNIT_POINTS.equals(unit) ? earned : BigDecimal.ZERO;
        BigDecimal pointsValue = UNIT_POINTS.equals(unit) ? value : zero();

        // Once-per-transaction measures ride on the first line of each transaction.
        boolean primary = reportLine.primary();
        boolean eligible = primary && reportLine.eligible();
        BigDecimal discount = eligible && reportLine.instantDiscount() != null ? reportLine.instantDiscount() : BigDecimal.ZERO;
        BigDecimal fee = eligible && reportLine.convenienceFee() != null ? reportLine.convenienceFee() : BigDecimal.ZERO;

        String rule = line.ruleId() == null ? NONE
                : ruleLabels.getOrDefault(line.ruleId(), line.ruleName() != null ? line.ruleName() : NONE);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", line.transactionId() + "_" + index);
        map.put("effectiveDate", line.effectiveDate());
        map.put("transactionDate", line.transactionDate());
        map.put("card", account.getName());
        map.put("cardId", account.getId().toString());
        map.put("cardholder", line.cardLabel() != null ? line.cardLabel() : UNATTRIBUTED);
        map.put("rule", rule);
        map.put("ruleId", line.ruleId() != null ? line.ruleId().toString() : null);
        map.put("category", reportLine.categories());
        map.put("reason", line.reason() != null ? line.reason().name() : null);
        map.put("earnedUnit", unit);
        map.put("stacking", line.stacking() != null ? line.stacking().name() : null);
        map.put("accrualType", line.accrualType() != null ? line.accrualType().name() : null);
        map.put("channel", line.channel() != null ? line.channel().name() : null);
        map.put("mcc", line.mcc());
        map.put("cycle", period(reportLine.cycleStart(), reportLine.cycleEnd()));
        map.put("rewardYear", period(reportLine.rewardYearStart(), reportLine.rewardYearEnd()));
        map.put("description", line.description());
        map.put("valueInr", value);
        map.put("cashInr", cash);
        map.put("points", points);
        map.put("pointsValueInr", pointsValue);
        map.put("netValueInr", value.add(discount).subtract(fee));
        map.put("earned", line.earned());
        map.put("spend", eligible ? reportLine.spend() : BigDecimal.ZERO);
        map.put("amount", primary ? line.amount() : BigDecimal.ZERO);
        map.put("basis", line.basis());
        map.put("instantDiscount", discount);
        map.put("convenienceFee", fee);
        map.put("txnCount", eligible ? BigDecimal.ONE : BigDecimal.ZERO);
        return map;
    }

    private List<FieldDef> buildCatalog() {
        List<String> reasonValues = names(RewardLineReason.values());
        List<String> channelValues = names(TransactionChannel.values());
        List<String> unitValues = List.of(UNIT_RUPEES, UNIT_POINTS);

        return List.of(
                FieldDef.cycleDate("effectiveDate", "Effective Date", CHART_TABLE),
                FieldDef.cycleDate("transactionDate", "Transaction Date", TABLE_ONLY),
                new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE, null, "cardId"),
                new FieldDef("cardholder", "Cardholder", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("rule", "Rule", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE, null, "ruleId"),
                new FieldDef("category", "Category", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("reason", "Reason", FieldType.ENUM, FieldRole.DIMENSION, null, reasonValues, null, CHART_TABLE),
                new FieldDef("earnedUnit", "Paid in", FieldType.ENUM, FieldRole.DIMENSION, null, unitValues, null, CHART_TABLE),
                new FieldDef("stacking", "Stacking", FieldType.ENUM, FieldRole.DIMENSION, null, names(RuleStacking.values()), null, CHART_TABLE),
                new FieldDef("accrualType", "Accrual type", FieldType.ENUM, FieldRole.DIMENSION, null, names(AccrualType.values()), null, CHART_TABLE),
                new FieldDef("channel", "Channel", FieldType.ENUM, FieldRole.DIMENSION, null, channelValues, null, CHART_TABLE),
                new FieldDef("mcc", "MCC", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("cycle", "Billing cycle", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("rewardYear", "Reward year", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("valueInr", "Reward value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("cashInr", "Cashback (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("points", "Points", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("pointsValueInr", "Points value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("netValueInr", "Net value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("earned", "Earned (raw units)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("spend", "Eligible spend (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("amount", "Transaction amount (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("basis", "Rule basis (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("instantDiscount", "Instant discount (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("convenienceFee", "Convenience fee (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("txnCount", "Eligible transactions", FieldType.NUMBER, FieldRole.MEASURE, SUM_ONLY, null, null, KPI_CHART_TABLE, "number")
        );
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
