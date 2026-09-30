package com.financeos.domain.report.datasource.impl;

import com.financeos.api.reward.dto.RewardReportResponse.MilestoneStatus;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.MilestoneWindow;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_POINTS;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_RUPEES;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.valueInr;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.zero;

/**
 * Computed report datasource: one row per milestone × window, the same window statuses the
 * Rewards page shows, across every card with milestones and over all of its history.
 *
 * <p>{@code payoutValueInr} is the rupee value of an ACHIEVED {@code CASH_VALUE} milestone
 * (points valued at the card's {@code pointValueInr}); tracker-only and unachieved windows
 * are worth zero. Filter on {@code payoutDate} to reproduce the Rewards page's milestone
 * total for a range: each payout lands on exactly one payout date.
 */
@Component
public class RewardMilestonesDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> NONE = List.of();

    private final RewardCalculationService rewardCalculationService;
    private final RewardReportSupport support;
    private final List<FieldDef> fields;

    public RewardMilestonesDatasource(RewardCalculationService rewardCalculationService,
                                      RewardReportSupport support) {
        this.rewardCalculationService = rewardCalculationService;
        this.support = support;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "reward_milestones";
    }

    @Override
    public String label() {
        return "Reward Milestones";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return List.of();
        }
        List<Account> accounts = support.milestoneAccounts(userId);
        if (accounts.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> labels = support.milestoneLabels(accounts);
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Account account : accounts) {
            // A card with milestones but no spend still has its current windows (at 0 progress).
            RewardReportSupport.DateBounds bounds = support.bounds(account.getId());
            LocalDate from = bounds != null ? bounds.from() : today;
            LocalDate to = bounds != null ? bounds.to() : today;
            List<MilestoneStatus> statuses = rewardCalculationService.milestoneStatuses(account.getId(), from, to);
            if (statuses == null) {
                continue;
            }
            for (MilestoneStatus status : statuses) {
                rows.add(row(status, account, labels));
            }
        }
        return rows;
    }

    private Map<String, Object> row(MilestoneStatus status, Account account, Map<UUID, String> labels) {
        String unit = status.rewardType() == RewardType.POINTS ? UNIT_POINTS : UNIT_RUPEES;
        boolean pays = status.achieved() && status.payoutType() == MilestonePayoutType.CASH_VALUE;
        BigDecimal payoutValueInr = pays ? valueInr(status.payoutValue(), unit, account.getPointValueInr()) : zero();
        BigDecimal progressPct = status.threshold() == null || status.threshold().signum() == 0 ? null
                : status.progress().multiply(BigDecimal.valueOf(100))
                        .divide(status.threshold(), 2, RoundingMode.HALF_UP);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", status.milestoneId() + "_" + status.windowStart());
        map.put("windowStart", status.windowStart());
        map.put("windowEnd", status.windowEnd());
        map.put("payoutDate", status.payoutDate());
        map.put("card", account.getName());
        map.put("milestone", labels.getOrDefault(status.milestoneId(), status.name()));
        map.put("windowType", status.windowType() != null ? status.windowType().name() : null);
        map.put("basis", status.basis() != null ? status.basis().name() : null);
        map.put("payoutType", status.payoutType() != null ? status.payoutType().name() : null);
        map.put("rewardType", status.rewardType() != null ? status.rewardType().name() : null);
        map.put("achieved", status.achieved());
        map.put("threshold", status.threshold());
        map.put("progress", status.progress());
        map.put("progressPct", progressPct);
        map.put("payoutValue", status.payoutValue());
        map.put("payoutValueInr", payoutValueInr);
        return map;
    }

    private List<FieldDef> buildCatalog() {
        return List.of(
                new FieldDef("windowStart", "Window start", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("windowEnd", "Window end", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("payoutDate", "Payout date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("milestone", "Milestone", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("windowType", "Window", FieldType.ENUM, FieldRole.DIMENSION, null, names(MilestoneWindow.values()), null, CHART_TABLE),
                new FieldDef("basis", "Counts", FieldType.ENUM, FieldRole.DIMENSION, null, names(MilestoneBasis.values()), null, CHART_TABLE),
                new FieldDef("payoutType", "Payout type", FieldType.ENUM, FieldRole.DIMENSION, null, names(MilestonePayoutType.values()), null, CHART_TABLE),
                new FieldDef("rewardType", "Paid in", FieldType.ENUM, FieldRole.DIMENSION, null, names(RewardType.values()), null, CHART_TABLE),
                new FieldDef("achieved", "Achieved", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
                new FieldDef("threshold", "Target", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("progress", "Progress", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("progressPct", "Progress (%)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "percent"),
                new FieldDef("payoutValue", "Payout (raw units)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("payoutValueInr", "Payout value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency")
        );
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
