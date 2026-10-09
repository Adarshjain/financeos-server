package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.engine.ReportData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Explains a {@code net_worth} row. The row id is an account, loan or counterparty id; it is
 * resolved by looking it up as each in turn, among the current user's rows of net worth today
 * (an excluded or closed account, an inactive loan or a settled counterparty is no row and
 * answers 404). Each kind recomputes its value through the same services and
 * {@link NetWorthPlacement} rules the datasource uses, so the breakdown's total is the row's value.
 */
@Component
public class NetWorthBreakdownProvider implements RowBreakdownProvider {

    static final String DATASOURCE = "net_worth";

    private final List<NetWorthItemBreakdown> kinds;

    public NetWorthBreakdownProvider(NetWorthAccountBreakdown accounts, NetWorthLoanBreakdown loans,
                                     NetWorthLendingBreakdown lendings) {
        this.kinds = List.of(accounts, loans, lendings);
    }

    @Override
    public String datasource() {
        return DATASOURCE;
    }

    @Override
    public RowBreakdownResponse breakdown(String rowId, int size) {
        return resolve(rowId, (kind, id) -> kind.breakdown(id, size));
    }

    @Override
    public ReportData section(String rowId, String section, int page, int size) {
        return resolve(rowId, (kind, id) -> kind.section(id, section, page, size));
    }

    private <T> T resolve(String rowId, BiFunction<NetWorthItemBreakdown, UUID, Optional<T>> lookup) {
        UUID id = parse(rowId);
        for (NetWorthItemBreakdown kind : kinds) {
            Optional<T> found = lookup.apply(kind, id);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new ResourceNotFoundException("Net worth row", rowId);
    }

    private static UUID parse(String rowId) {
        try {
            return UUID.fromString(rowId);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Net worth row", rowId);
        }
    }

    /**
     * The response shared by every kind: titled by the item's name, subtitled by its side, with
     * the row's value as the total (the chain's {@code equals} step, labelled {@code totalLabel},
     * closes on it).
     */
    static RowBreakdownResponse response(UUID id, String name, String kindLabel, NetWorthPlacement placement,
                                         String totalLabel, List<BreakdownStep> steps,
                                         List<BreakdownSectionData> sections, List<String> notes) {
        return new RowBreakdownResponse(DATASOURCE, id.toString(), name,
                placement.side() == FinancialPosition.asset ? "Asset" : "Liability", kindLabel,
                placement.value(), totalLabel, BreakdownChain.CURRENCY, AppTime.today(), steps, sections, notes);
    }
}
