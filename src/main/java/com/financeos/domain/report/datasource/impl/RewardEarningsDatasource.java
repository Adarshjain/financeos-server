package com.financeos.domain.report.datasource.impl;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionRepository;
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

/**
 * Computed report datasource: one row per transaction × rule reward evaluation line.
 *
 * <p>Caveats:
 * <ul>
 *   <li>Point valuation: unvalued points (account {@code pointValueInr} is null) contribute
 *       zero to {@code valueInr}; the raw {@code earned} and {@code earnedUnit} columns keep them visible.</li>
 *   <li>{@code earned} is mixed units across rows (rupees or points) — users filter or group by {@code earnedUnit}.</li>
 *   <li>Milestone payouts are per-window and not included on transaction lines.</li>
 * </ul>
 */
@Component
public class RewardEarningsDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final RewardCalculationService rewardCalculationService;
    private final AccountRepository accountRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final TransactionRepository transactionRepository;
    private final List<FieldDef> fields;

    public RewardEarningsDatasource(
            RewardCalculationService rewardCalculationService,
            AccountRepository accountRepository,
            RewardRuleRepository rewardRuleRepository,
            TransactionRepository transactionRepository) {
        this.rewardCalculationService = rewardCalculationService;
        this.accountRepository = accountRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.transactionRepository = transactionRepository;
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
    public List<Map<String, Object>> rows() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return List.of();
        }

        List<UUID> accountIds = rewardRuleRepository.findDistinctAccountIdsByUserId(userId);
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int i = 0;
        LocalDate today = LocalDate.now();

        for (UUID accountId : accountIds) {
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null) {
                continue;
            }

            LocalDate minDate = transactionRepository.findMinDateByAccountId(accountId);
            if (minDate == null) {
                continue;
            }

            List<RewardLineResponse> lines = rewardCalculationService.lines(accountId, minDate, today, null);
            if (lines == null || lines.isEmpty()) {
                continue;
            }

            for (RewardLineResponse line : lines) {
                BigDecimal valueInr;
                if ("RUPEES".equals(line.earnedUnit())) {
                    valueInr = line.earned() != null
                            ? line.earned().setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                } else if ("POINTS".equals(line.earnedUnit())) {
                    if (account.getPointValueInr() != null && line.earned() != null) {
                        valueInr = line.earned().multiply(account.getPointValueInr()).setScale(2, RoundingMode.HALF_UP);
                    } else {
                        valueInr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    }
                } else {
                    valueInr = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", line.transactionId() + "_" + (i++));
                map.put("effectiveDate", line.effectiveDate());
                map.put("transactionDate", line.transactionDate());
                map.put("card", account.getName());
                map.put("rule", line.ruleName() != null ? line.ruleName() : "(none)");
                map.put("reason", line.reason() != null ? line.reason().name() : null);
                map.put("earnedUnit", line.earnedUnit());
                map.put("channel", line.channel() != null ? line.channel().name() : null);
                map.put("mcc", line.mcc());
                map.put("description", line.description());
                map.put("valueInr", valueInr);
                map.put("earned", line.earned());
                map.put("basis", line.basis());
                map.put("amount", line.amount());

                rows.add(map);
            }
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> reasonValues = Arrays.stream(RewardLineReason.values()).map(Enum::name).toList();
        List<String> channelValues = Arrays.stream(TransactionChannel.values()).map(Enum::name).toList();
        List<String> unitValues = List.of("RUPEES", "POINTS");

        return List.of(
                new FieldDef("effectiveDate", "Effective Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("transactionDate", "Transaction Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("rule", "Rule", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("reason", "Reason", FieldType.ENUM, FieldRole.DIMENSION, null, reasonValues, null, CHART_TABLE),
                new FieldDef("earnedUnit", "Paid in", FieldType.ENUM, FieldRole.DIMENSION, null, unitValues, null, CHART_TABLE),
                new FieldDef("channel", "Channel", FieldType.ENUM, FieldRole.DIMENSION, null, channelValues, null, CHART_TABLE),
                new FieldDef("mcc", "MCC", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("valueInr", "Reward Value (₹)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("earned", "Earned (raw units)", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "number"),
                new FieldDef("basis", "Basis", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency")
        );
    }
}
