package com.financeos.domain.report.breakdown;

import com.financeos.api.nps.dto.NpsSummaryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsService;
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
 * Breakdown of the NPS row of net worth: the value of the user's own accounts (a child's NPS is left out, as in the
 * row), one step per account and tier with a value, and every account and tier in a table with its value and
 * what was invested. The total is the one the datasource row uses ({@link NpsService#getSelfValueOrNull}); no row
 * (no own account) answers empty.
 */
@Component
@Transactional(readOnly = true)
class NetWorthNpsBreakdown {

    static final String ACCOUNTS = "accounts";

    private static final List<TableData.Column> COLUMNS = List.of(
            new TableData.Column("name", "Account", "string", null),
            new TableData.Column("tier", "Tier", "string", null),
            new TableData.Column("value", "Value", "number", "currency"),
            new TableData.Column("invested", "Invested", "number", "currency"));

    private final NpsService npsService;

    NetWorthNpsBreakdown(NpsService npsService) {
        this.npsService = npsService;
    }

    Optional<RowBreakdownResponse> breakdown(int size) {
        BigDecimal total = npsService.getSelfValueOrNull();
        if (total == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = rows();
        NetWorthPlacement placement = new NetWorthPlacement(FinancialPosition.asset, total.max(BigDecimal.ZERO));

        BreakdownChain chain = new BreakdownChain(placement.value());
        for (Map<String, Object> r : rows) {
            BigDecimal value = (BigDecimal) r.get("value");
            if (value.signum() != 0) {
                chain.term(r.get("name") + " " + r.get("tier"), value);
            }
        }
        String totalLabel = "NPS value";
        List<BreakdownStep> steps = chain.close(totalLabel, placement.value(), "nps");

        BreakdownSectionData section = new BreakdownSectionData(ACCOUNTS, "Accounts", null, null,
                BreakdownTables.sorted(COLUMNS, rows, null, 0, size));
        String kindLabel = NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_NPS);
        return Optional.of(new RowBreakdownResponse(NetWorthBreakdownProvider.DATASOURCE,
                NetWorthDatasource.NPS_ROW_ID, "NPS", NetWorthDatasource.sideLabel(placement.side()), kindLabel,
                placement.value(), totalLabel, BreakdownChain.CURRENCY, AppTime.today(), steps, List.of(section),
                List.of()));
    }

    Optional<ReportData> section(String section, int page, int size, @Nullable SortClause sort) {
        if (!ACCOUNTS.equals(section)) {
            throw new ResourceNotFoundException("Breakdown section", section);
        }
        if (sort != null) {
            BreakdownTables.requireColumn(COLUMNS, sort);
        }
        if (npsService.getSelfValueOrNull() == null) {
            return Optional.empty();
        }
        return Optional.of(BreakdownTables.sorted(COLUMNS, rows(), sort, page, size));
    }

    /** One row per own account and tier, as the summary shows them. */
    private List<Map<String, Object>> rows() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (NpsSummaryResponse.Account a : npsService.getSummary().accounts()) {
            if (a.relation() != NpsRelation.SELF) {
                continue;
            }
            for (NpsSummaryResponse.Tier t : a.tiers()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", a.id() + "-" + t.tier());
                row.put("name", a.holderName() != null && !a.holderName().isBlank() ? a.holderName() : a.pranMasked());
                row.put("tier", "Tier " + t.tier());
                row.put("value", t.value());
                row.put("invested", t.invested());
                out.add(row);
            }
        }
        return out;
    }
}
