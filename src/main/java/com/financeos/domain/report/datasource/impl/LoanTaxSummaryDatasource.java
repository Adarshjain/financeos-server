package com.financeos.domain.report.datasource.impl;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanType;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.engine.BucketLabels;
import com.financeos.domain.report.engine.DateRangeResolver;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LoanTaxSummaryDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final LoanService loanService;
    private final DateRangeResolver dateRangeResolver;
    private final List<FieldDef> fields;

    public LoanTaxSummaryDatasource(LoanService loanService, DateRangeResolver dateRangeResolver) {
        this.loanService = loanService;
        this.dateRangeResolver = dateRangeResolver;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "loan_tax_summary";
    }

    @Override
    public String label() {
        return "Loan Tax Summary";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        List<LoanService.LoanWithSchedule> loansWithSchedule = loanService.getAllLoansWithSchedule();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (LoanService.LoanWithSchedule item : loansWithSchedule) {
            Loan loan = item.loan();
            if (item.schedule() == null || item.schedule().installments() == null) {
                continue;
            }

            Map<String, FyBucket> fyBuckets = new LinkedHashMap<>();

            for (InstallmentDto installment : item.schedule().installments()) {
                if (!"settled".equals(installment.status()) || installment.payment() == null
                        || installment.payment().paymentDate() == null) {
                    continue;
                }

                LocalDate paymentDate = installment.payment().paymentDate();
                LocalDate fyStart = dateRangeResolver.fiscalYearStart(paymentDate);
                String fy = BucketLabels.bucketLabel(fyStart, Granularity.FY, dateRangeResolver.getFiscalYearStartMonth());

                FyBucket bucket = fyBuckets.computeIfAbsent(fy, k -> new FyBucket());
                BigDecimal interest = installment.interest() != null ? installment.interest() : BigDecimal.ZERO;
                BigDecimal principal = installment.principal() != null ? installment.principal() : BigDecimal.ZERO;
                bucket.interestPaid = bucket.interestPaid.add(interest);
                bucket.principalPaid = bucket.principalPaid.add(principal);
            }

            for (Map.Entry<String, FyBucket> entry : fyBuckets.entrySet()) {
                String fy = entry.getKey();
                FyBucket bucket = entry.getValue();

                BigDecimal interestPaid = bucket.interestPaid;
                BigDecimal principalPaid = bucket.principalPaid;

                boolean isHome = loan.getLoanType() == LoanType.home;
                boolean isEducation = loan.getLoanType() == LoanType.education;

                BigDecimal sec24bInterest = isHome ? interestPaid : BigDecimal.ZERO;
                BigDecimal sec80cPrincipal = isHome ? principalPaid : BigDecimal.ZERO;
                BigDecimal sec80eInterest = isEducation ? interestPaid : BigDecimal.ZERO;

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", loan.getId() + "_" + fy);
                map.put("financialYear", fy);
                map.put("loanId", loan.getId() != null ? loan.getId().toString() : null);
                map.put("loanName", loan.getName());
                map.put("loanType", loan.getLoanType() != null ? loan.getLoanType().name() : null);
                map.put("lender", loan.getLender());
                map.put("interestPaid", interestPaid);
                map.put("principalPaid", principalPaid);
                map.put("sec24bInterest", sec24bInterest);
                map.put("sec80cPrincipal", sec80cPrincipal);
                map.put("sec80eInterest", sec80eInterest);

                rows.add(map);
            }
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> loanTypeValues = Arrays.stream(LoanType.values()).map(Enum::name).toList();

        return List.of(
                new FieldDef("financialYear", "Financial Year", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("loanId", "Loan ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("loanName", "Loan", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("loanType", "Loan Type", FieldType.ENUM, FieldRole.DIMENSION, null, loanTypeValues, null, CHART_TABLE),
                new FieldDef("lender", "Lender", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("interestPaid", "Interest Paid", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("principalPaid", "Principal Paid", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("sec24bInterest", "Sec 24(b) Interest", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("sec80cPrincipal", "Sec 80C Principal", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("sec80eInterest", "Sec 80E Interest", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency")
        );
    }

    private static class FyBucket {
        BigDecimal interestPaid = BigDecimal.ZERO;
        BigDecimal principalPaid = BigDecimal.ZERO;
    }
}
