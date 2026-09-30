package com.financeos.domain.report.datasource.impl;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCalculationService.CapUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_POINTS;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.UNIT_RUPEES;
import static com.financeos.domain.report.datasource.impl.RewardReportSupport.valueInr;

/**
 * Computed report datasource: one row per period cap × window (× cardholder for caps that
 * count per cardholder), across every card with reward rules and all of its history. A
 * shared cap bucket is one row per window however many rules drain it. Only windows where
 * the cap was drawn on exist — a window with no earning has no row.
 */
@Component
public class RewardCapsDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    static final String YES = "Yes";
    static final String NO = "No";
    private static final List<String> YES_NO = List.of(YES, NO);

    static final String ALL_CARDHOLDERS = "All cardholders";
    static final String RULE_CAP = "RULE";
    static final String SHARED_BUCKET = "SHARED_BUCKET";

    private final RewardCalculationService rewardCalculationService;
    private final RewardReportSupport support;
    private final List<FieldDef> fields;

    public RewardCapsDatasource(RewardCalculationService rewardCalculationService,
                                RewardReportSupport support) {
        this.rewardCalculationService = rewardCalculationService;
        this.support = support;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "reward_caps";
    }

    @Override
    public String label() {
        return "Reward Caps";
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
        List<Account> accounts = support.ruleAccounts(userId);
        if (accounts.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> ruleLabels = support.ruleLabels(accounts);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Account account : accounts) {
            RewardReportSupport.DateBounds bounds = support.bounds(account.getId());
            if (bounds == null) {
                continue;
            }
            List<CapUsage> usages = rewardCalculationService.capUsage(account.getId(), bounds.from(), bounds.to());
            if (usages == null) {
                continue;
            }
            for (CapUsage usage : usages) {
                rows.add(row(usage, account, ruleLabels));
            }
        }
        return rows;
    }

    private Map<String, Object> row(CapUsage usage, Account account, Map<UUID, String> ruleLabels) {
        boolean bucket = usage.bucketName() != null;
        String cap = bucket ? usage.bucketName() : ruleLabels.getOrDefault(usage.ruleId(), usage.ruleName());
        BigDecimal remaining = usage.cap().subtract(usage.used()).max(BigDecimal.ZERO);
        BigDecimal utilization = usage.cap().signum() == 0 ? null
                : usage.used().multiply(BigDecimal.valueOf(100)).divide(usage.cap(), 2, RoundingMode.HALF_UP);

        Map<String, Object> map = new LinkedHashMap<>();
        String capId = bucket ? "bucket:" + usage.bucketId() : "rule:" + usage.ruleId();
        map.put("id", account.getId() + "_" + capId + "_" + usage.windowStart() + "_" + usage.cardholderId());
        map.put("windowStart", usage.windowStart());
        map.put("windowEnd", usage.windowEnd());
        map.put("card", account.getName());
        map.put("cardId", account.getId().toString());
        map.put("cap", cap);
        map.put("capId", capId);
        map.put("capType", bucket ? SHARED_BUCKET : RULE_CAP);
        map.put("window", usage.window().name());
        map.put("cardholder", usage.cardholderLabel() != null ? usage.cardholderLabel() : ALL_CARDHOLDERS);
        map.put("unit", usage.unit());
        map.put("capHit", usage.used().compareTo(usage.cap()) >= 0 ? YES : NO);
        map.put("cycleFallback", usage.cycleFallback() ? YES : NO);
        map.put("capLimit", usage.cap());
        map.put("used", usage.used());
        map.put("remaining", remaining);
        map.put("utilizationPct", utilization);
        map.put("usedValueInr", valueInr(usage.used(), usage.unit(), account.getPointValueInr()));
        return map;
    }

    private List<FieldDef> buildCatalog() {
        return List.of(
                new FieldDef("windowStart", "Window start", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("windowEnd", "Window end", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE, null, "cardId"),
                new FieldDef("cap", "Cap", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE, null, "capId"),
                new FieldDef("capType", "Cap type", FieldType.ENUM, FieldRole.DIMENSION, null, List.of(RULE_CAP, SHARED_BUCKET), null, CHART_TABLE),
                new FieldDef("window", "Cap window", FieldType.ENUM, FieldRole.DIMENSION, null,
                        Arrays.stream(CapWindow.values()).map(Enum::name).toList(), null, CHART_TABLE),
                new FieldDef("cardholder", "Cardholder", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("unit", "Unit", FieldType.ENUM, FieldRole.DIMENSION, null, List.of(UNIT_RUPEES, UNIT_POINTS), null, CHART_TABLE),
                new FieldDef("capHit", "Cap reached", FieldType.ENUM, FieldRole.DIMENSION, null, YES_NO, null, CHART_TABLE),
                new FieldDef("cycleFallback", "Estimated cycle", FieldType.ENUM, FieldRole.DIMENSION, null, YES_NO, null, CHART_TABLE),
                new FieldDef("capLimit", "Cap limit", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("used", "Used", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("remaining", "Remaining", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("utilizationPct", "Utilisation (%)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "percent"),
                new FieldDef("usedValueInr", "Used value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency")
        );
    }
}
