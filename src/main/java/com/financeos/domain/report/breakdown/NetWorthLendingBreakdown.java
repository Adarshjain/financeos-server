package com.financeos.domain.report.breakdown;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Breakdown of a counterparty's row of net worth: its net position, money out (everything you lent
 * or paid them) minus money in (everything they paid you or you borrowed), whatever each entry's
 * kind — the ledger totals {@link LendingService} lists the counterparty with.
 */
@Component
@Transactional(readOnly = true)
class NetWorthLendingBreakdown implements NetWorthItemBreakdown {

    static final String ENTRIES = "entries";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("entryDate"), Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private static final List<TableData.Column> ENTRY_COLUMNS = List.of(
            new TableData.Column("date", "Date", "date", null),
            new TableData.Column("direction", "Direction", "string", null),
            new TableData.Column("kind", "Kind", "string", null),
            new TableData.Column("amount", "Amount", "number", "currency"),
            new TableData.Column("notes", "Notes", "string", null));

    private final LendingService lendingService;
    private final LendingRepository lendingRepository;

    NetWorthLendingBreakdown(LendingService lendingService, LendingRepository lendingRepository) {
        this.lendingService = lendingService;
        this.lendingRepository = lendingRepository;
    }

    @Override
    public Optional<RowBreakdownResponse> breakdown(UUID id, int size) {
        return countedCounterparty(id).map(cp -> breakdown(cp, size));
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size) {
        return section(id, section, page, size, null);
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size, @Nullable SortClause sort) {
        return countedCounterparty(id).map(cp -> {
            if (!ENTRIES.equals(section)) {
                throw new ResourceNotFoundException("Breakdown section", section);
            }
            if (sort == null) {
                return entriesTable(cp.id(), page, size);
            }
            // Sorted over every entry (newest first as the tiebreak), then paged.
            BreakdownTables.requireColumn(ENTRY_COLUMNS, sort);
            List<Map<String, Object>> rows = lendingRepository.findByCounterparty_Id(cp.id(), NEWEST_FIRST).stream()
                    .map(NetWorthLendingBreakdown::entryRow)
                    .toList();
            return BreakdownTables.sorted(ENTRY_COLUMNS, rows, sort, page, size);
        });
    }

    /** The user's counterparty when it is a row of net worth (a non-zero net position). */
    private Optional<CounterpartyResponse> countedCounterparty(UUID id) {
        return lendingService.findOwnedCounterparty(id).filter(cp -> NetWorthPlacement.ofLending(cp) != null);
    }

    private RowBreakdownResponse breakdown(CounterpartyResponse cp, int size) {
        List<Lending> entries = lendingRepository.findByCounterparty_Id(cp.id());
        long out = entries.stream().filter(l -> l.getDirection() == LendingDirection.lent).count();
        long in = entries.stream().filter(l -> l.getDirection() == LendingDirection.borrowed).count();

        BreakdownChain chain = new BreakdownChain(cp.netPosition());
        if (out > 0) {
            chain.term("You lent / paid them (" + out + ")", cp.totalLent().add(cp.repaidByYou()));
        }
        if (in > 0) {
            chain.term("They paid you / you borrowed (" + in + ")", cp.totalBorrowed().add(cp.repaidToYou()).negate());
        }
        String totalLabel = cp.netPosition().signum() > 0 ? "They owe you" : "You owe them";
        NetWorthPlacement placement = NetWorthPlacement.ofLending(cp);
        List<BreakdownStep> steps = chain.close(totalLabel, placement.value(), "counterparty " + cp.id());

        BreakdownSectionData section = new BreakdownSectionData(ENTRIES, "Entries", null, null,
                entriesTable(cp.id(), 0, size));
        return NetWorthBreakdownProvider.response(cp.id(), cp.name(),
                NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_LENDING), placement, totalLabel, steps,
                List.of(section), List.of());
    }

    private TableData entriesTable(UUID counterpartyId, int page, int size) {
        Page<Lending> result = lendingRepository.findByCounterparty_Id(counterpartyId,
                PageRequest.of(page, size, NEWEST_FIRST));
        List<Map<String, Object>> rows = result.getContent().stream().map(NetWorthLendingBreakdown::entryRow).toList();
        return BreakdownTables.page(ENTRY_COLUMNS, rows, page, size, result.getTotalElements());
    }

    private static Map<String, Object> entryRow(Lending l) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.getId().toString());
        row.put("date", l.getEntryDate());
        row.put("direction", l.getDirection() == LendingDirection.lent ? "Lent" : "Borrowed");
        row.put("kind", l.getKind() == LendingKind.settlement ? "Settlement" : "Principal");
        row.put("amount", l.getAmount());
        row.put("notes", l.getNotes());
        return row;
    }
}
