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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LoanPaymentsDatasource implements ComputedReportDatasource {

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);
    private static final List<Aggregation> NON_SUM_AGGS = List.of(
            Aggregation.AVG, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    private final LoanService loanService;
    private final List<FieldDef> fields;

    public LoanPaymentsDatasource(LoanService loanService) {
        this.loanService = loanService;
        this.fields = buildCatalog();
    }

    @Override
    public String name() {
        return "loan_payments";
    }

    @Override
    public String label() {
        return "Loan Payments";
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

            for (InstallmentDto installment : item.schedule().installments()) {
                if (!"settled".equals(installment.status()) || installment.payment() == null) {
                    continue;
                }

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", installment.payment().id() != null
                        ? installment.payment().id().toString()
                        : loan.getId() + "_" + installment.seq());
                map.put("loanId", loan.getId() != null ? loan.getId().toString() : null);
                map.put("loanName", loan.getName());
                map.put("loanType", loan.getLoanType() != null ? loan.getLoanType().name() : null);
                map.put("lender", loan.getLender());
                map.put("installmentSeq", installment.seq());
                map.put("dueDate", installment.dueDate());
                map.put("paymentDate", installment.payment().paymentDate());
                map.put("paidAmount", installment.payment().amount());
                map.put("scheduledEmi", installment.emi());
                map.put("interestComponent", installment.interest());
                map.put("principalComponent", installment.principal());

                rows.add(map);
            }
        }

        return rows;
    }

    private List<FieldDef> buildCatalog() {
        List<String> loanTypeValues = Arrays.stream(LoanType.values()).map(Enum::name).toList();

        return List.of(
                new FieldDef("paymentDate", "Payment Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("dueDate", "Due Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("loanId", "Loan ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
                new FieldDef("loanName", "Loan", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("loanType", "Loan Type", FieldType.ENUM, FieldRole.DIMENSION, null, loanTypeValues, null, CHART_TABLE),
                new FieldDef("lender", "Lender", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
                new FieldDef("installmentSeq", "Installment #", FieldType.NUMBER, FieldRole.MEASURE, NON_SUM_AGGS, null, null, TABLE_ONLY, "number"),
                new FieldDef("paidAmount", "Paid Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("scheduledEmi", "Scheduled EMI", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("interestComponent", "Interest Component", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency"),
                new FieldDef("principalComponent", "Principal Component", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, KPI_CHART_TABLE, "currency")
        );
    }
}
