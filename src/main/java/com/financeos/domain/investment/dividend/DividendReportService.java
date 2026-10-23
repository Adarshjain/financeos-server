package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendInstrumentTotalResponse;
import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.util.CsvText;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.investment.FinancialYear;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only dividend reports: totals per instrument over recent financial years, and the CSV export. */
@Service
@Transactional(readOnly = true)
public class DividendReportService {

    public static final int DEFAULT_YEARS = 3;
    public static final int MAX_YEARS = 20;
    static final int EXPORT_BATCH = 500;

    private static final List<String> EXPORT_HEADER = List.of(
            "Instrument", "Broker account", "Type", "Ex-date", "Pay date", "Per unit", "Quantity",
            "Gross", "TDS", "Net", "Receipt status", "Bank transaction", "Bank transaction date");

    private final DividendRepository dividendRepository;
    private final InstrumentRepository instrumentRepository;
    private final DividendReceiptStatusResolver resolver;
    private final DividendService dividendService;

    public DividendReportService(DividendRepository dividendRepository, InstrumentRepository instrumentRepository,
                                 DividendReceiptStatusResolver resolver, DividendService dividendService) {
        this.dividendRepository = dividendRepository;
        this.instrumentRepository = instrumentRepository;
        this.resolver = resolver;
        this.dividendService = dividendService;
    }

    /**
     * Totals per instrument over the last {@code years} financial years (the current one included),
     * biggest net first; names and symbols as the user's instrument overrides show them.
     */
    public Page<DividendInstrumentTotalResponse> byInstrument(int years, Pageable pageable) {
        if (years < 1 || years > MAX_YEARS) {
            throw new ValidationException("years must be between 1 and " + MAX_YEARS);
        }
        int currentFy = FinancialYear.current();
        LocalDate from = FinancialYear.start(currentFy - years + 1);
        LocalDate to = FinancialYear.end(currentFy);
        Page<Object[]> page = dividendRepository.findTotalsByInstrument(from, to,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<UUID> ids = page.getContent().stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, Instrument> instruments = instrumentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Instrument::getId, Function.identity()));
        InstrumentOverrides overrides = resolver.overrides();
        return page.map(r -> {
            Instrument instrument = instruments.get((UUID) r[0]);
            BigDecimal gross = (BigDecimal) r[1];
            BigDecimal tds = (BigDecimal) r[2];
            return new DividendInstrumentTotalResponse(
                    instrument.getId(), overrides.name(instrument), overrides.symbol(instrument),
                    gross, tds, gross.subtract(tds), ((Number) r[3]).longValue());
        });
    }

    /** The file name suffix: the financial year ("2026-27") or "all". */
    public static String exportLabel(Integer fy) {
        return fy != null ? FinancialYear.label(fy) : "all";
    }

    /** Every dividend matching the list filters as CSV (UTF-8 with a byte-order mark), read in batches. */
    public byte[] exportCsv(UUID holdingId, UUID brokerAccountId, UUID instrumentId, DividendType type,
                            LocalDate from, LocalDate to, DividendReceiptStatus receipt) {
        StringBuilder out = new StringBuilder("﻿");
        out.append(CsvText.row(EXPORT_HEADER));
        Sort sort = Sort.by(Sort.Order.desc("payDate"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        Page<DividendResponse> page;
        int number = 0;
        do {
            page = dividendService.getDividends(holdingId, brokerAccountId, instrumentId, type, from, to, receipt,
                    PageRequest.of(number++, EXPORT_BATCH, sort));
            for (DividendResponse d : page.getContent()) {
                out.append(CsvText.row(exportRow(d)));
            }
        } while (page.hasNext());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<String> exportRow(DividendResponse d) {
        BigDecimal tds = d.tds() != null ? d.tds() : BigDecimal.ZERO;
        List<String> cells = new ArrayList<>();
        cells.add(CsvText.text(d.instrumentName()));
        cells.add(CsvText.text(d.brokerName()));
        cells.add(d.type().name());
        cells.add(d.exDate() != null ? d.exDate().toString() : null);
        cells.add(d.payDate().toString());
        cells.add(plain(d.perUnit()));
        cells.add(plain(quantity(d)));
        cells.add(plain(d.amount()));
        cells.add(plain(tds));
        cells.add(plain(d.amount().subtract(tds)));
        cells.add(d.receiptStatus().name());
        cells.add(d.transaction() != null ? CsvText.text(d.transaction().description()) : null);
        cells.add(d.transaction() != null ? d.transaction().date().toString() : null);
        return cells;
    }

    /** Units paid on, when the per-unit amount is known (gross / per unit). */
    static BigDecimal quantity(DividendResponse d) {
        if (d.perUnit() == null || d.perUnit().signum() <= 0) {
            return null;
        }
        return d.amount().divide(d.perUnit(), 4, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
