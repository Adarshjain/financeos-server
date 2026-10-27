package com.financeos.domain.report.breakdown;

import com.financeos.api.epf.dto.EpfEmployerResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.epf.EpfEmployerStatus;
import com.financeos.domain.epf.EpfService;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Breakdown of the EPF row of net worth: the balance over all employers, one step per employer with
 * a balance, and every employer in a table. The total is the one the datasource row uses
 * ({@link EpfService#getTotalBalanceOrNull}); no row (no employers) answers empty. The balance
 * counts what the passbook ledger holds, so pension (EPS) is left out.
 */
@Component
@Transactional(readOnly = true)
class NetWorthEpfBreakdown {

    static final String EMPLOYERS = "employers";

    private static final List<TableData.Column> EMPLOYER_COLUMNS = List.of(
            new TableData.Column("name", "Employer", "string", null),
            new TableData.Column("status", "Status", "string", null),
            new TableData.Column("balance", "Balance", "number", "currency"));

    private final EpfService epfService;

    NetWorthEpfBreakdown(EpfService epfService) {
        this.epfService = epfService;
    }

    Optional<RowBreakdownResponse> breakdown(int size) {
        BigDecimal total = epfService.getTotalBalanceOrNull();
        if (total == null) {
            return Optional.empty();
        }
        List<EpfEmployerResponse> employers = epfService.getEmployers();
        NetWorthPlacement placement = new NetWorthPlacement(FinancialPosition.asset, total.max(BigDecimal.ZERO));

        BreakdownChain chain = new BreakdownChain(placement.value());
        for (EpfEmployerResponse e : employers) {
            if (e.balance().signum() != 0) {
                chain.term(e.name(), e.balance());
            }
        }
        String totalLabel = "EPF balance";
        List<BreakdownStep> steps = chain.close(totalLabel, placement.value(), "epf");

        BreakdownSectionData section = new BreakdownSectionData(EMPLOYERS, "Employers", null, null,
                BreakdownTables.sorted(EMPLOYER_COLUMNS, rows(employers), null, 0, size));
        String kindLabel = NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_EPF);
        return Optional.of(new RowBreakdownResponse(NetWorthBreakdownProvider.DATASOURCE,
                NetWorthDatasource.EPF_ROW_ID, "EPF", NetWorthDatasource.sideLabel(placement.side()), kindLabel,
                placement.value(), totalLabel, BreakdownChain.CURRENCY, AppTime.today(), steps, List.of(section),
                List.of("Pension (EPS) is not counted.")));
    }

    Optional<ReportData> section(String section, int page, int size, @Nullable SortClause sort) {
        if (!EMPLOYERS.equals(section)) {
            throw new ResourceNotFoundException("Breakdown section", section);
        }
        if (sort != null) {
            BreakdownTables.requireColumn(EMPLOYER_COLUMNS, sort);
        }
        if (epfService.getTotalBalanceOrNull() == null) {
            return Optional.empty();
        }
        return Optional.of(BreakdownTables.sorted(EMPLOYER_COLUMNS, rows(epfService.getEmployers()), sort, page, size));
    }

    private static List<Map<String, Object>> rows(List<EpfEmployerResponse> employers) {
        return employers.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id().toString());
            row.put("name", e.name());
            row.put("status", statusLabel(e.status()));
            row.put("balance", e.balance());
            return row;
        }).toList();
    }

    private static String statusLabel(EpfEmployerStatus status) {
        return switch (status) {
            case CURRENT -> "Current";
            case LEFT -> "Left";
            case TRANSFERRED -> "Transferred";
            case SETTLED -> "Settled";
        };
    }
}
