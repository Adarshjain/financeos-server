package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Explains a {@code net_worth} row. The row id is an account, loan or counterparty id; it is
 * resolved by looking it up as each in turn, among the current user's rows of net worth today
 * (an inactive loan or a settled counterparty is no row and answers 404). An account net worth
 * leaves out (excluded or closed) is still explained, flagged {@code notCounted} with its reason,
 * so its balance can be followed from a surface that shows it outside net worth. Each kind recomputes its value through the same services and
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
    public ReportData section(String rowId, String section, int page, int size, @Nullable SortClause sort) {
        return resolve(rowId, (kind, id) -> sort == null
                ? kind.section(id, section, page, size)
                : kind.section(id, section, page, size, sort));
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
                NetWorthDatasource.sideLabel(placement.side()), kindLabel,
                placement.value(), totalLabel, BreakdownChain.CURRENCY, AppTime.today(), steps, sections, notes);
    }

    /** Subtitle of an item net worth leaves out today. */
    static final String NOT_COUNTED_SUBTITLE = "Not counted in net worth";
    static final String EXCLUDED_REASON = "Excluded from net worth";
    static final String CLOSED_REASON = "Closed";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * {@link #response} for an account; when net worth leaves it out today ({@code omission} non-null)
     * the response is subtitled {@link #NOT_COUNTED_SUBTITLE}, flagged {@code notCounted} with the
     * reason, and its first note says why the balance is not part of the total.
     */
    static RowBreakdownResponse accountResponse(UUID id, String name, String kindLabel, NetWorthPlacement placement,
                                                String totalLabel, List<BreakdownStep> steps,
                                                List<BreakdownSectionData> sections, List<String> notes,
                                                @Nullable NetWorthPlacement.Omission omission,
                                                @Nullable LocalDate closedOn) {
        if (omission == null) {
            return response(id, name, kindLabel, placement, totalLabel, steps, sections, notes);
        }
        String reason = omission == NetWorthPlacement.Omission.EXCLUDED ? EXCLUDED_REASON : CLOSED_REASON;
        String why = omission == NetWorthPlacement.Omission.EXCLUDED
                ? "This account is marked excluded from net worth, so its balance is not part of the total."
                : "This account was closed" + (closedOn != null
                        ? " on " + DAY.format(closedOn) : "")
                        + ", so its balance is not part of the total.";
        List<String> allNotes = new ArrayList<>();
        allNotes.add(why);
        allNotes.addAll(notes);
        return new RowBreakdownResponse(DATASOURCE, id.toString(), name, NOT_COUNTED_SUBTITLE, kindLabel,
                placement.value(), totalLabel, BreakdownChain.CURRENCY, AppTime.today(), steps, sections,
                List.copyOf(allNotes), true, reason);
    }
}
