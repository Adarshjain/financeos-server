package com.financeos.domain.investment.fno;

import com.financeos.api.investment.dto.FnoSummaryResponse;
import com.financeos.api.investment.dto.FnoTradeResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.util.CsvText;
import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.FinancialYear;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Paged listing, summary and ITR export of F&amp;O trades. A trade's date is its exit date, else its entry date. */
@Service
@Transactional(readOnly = true)
public class FnoReportService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 200;

    /** Contract filter: calls, puts or futures. */
    public enum ContractFilter { CE, PE, FUT }

    private static final List<String> EXPORT_HEADER = List.of(
            "Trade date", "Underlying", "Contract", "Strike", "Type", "Quantity", "Buy value", "Sell value",
            "Charges", "Realised P&L", "Turnover (absolute P&L)");

    private final FnoTradeRepository repository;

    public FnoReportService(FnoTradeRepository repository) {
        this.repository = repository;
    }

    public Page<FnoTradeResponse> page(Integer page, Integer size, Integer fy, ContractFilter contract,
                                       UUID brokerAccountId, String underlying, String sort) {
        int pageNo = page != null ? page : 0;
        int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;
        if (pageNo < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ValidationException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        FinancialYear.Range range = FinancialYear.resolve(fy, null, null);
        String underlyingKey = underlying == null || underlying.isBlank() ? null : underlying.trim().toUpperCase(Locale.ROOT);
        FnoContractType contractType = contract == ContractFilter.FUT ? FnoContractType.future : null;
        OptionType optionType = contract == ContractFilter.CE ? OptionType.CE : contract == ContractFilter.PE ? OptionType.PE : null;
        Pageable pageable = PageRequest.of(pageNo, pageSize);
        String order = sort == null || sort.isBlank() ? "newest" : sort;
        Page<FnoTrade> result = switch (order) {
            case "newest" -> repository.pageNewest(range.from(), range.to(), brokerAccountId, underlyingKey, contractType, optionType, pageable);
            case "oldest" -> repository.pageOldest(range.from(), range.to(), brokerAccountId, underlyingKey, contractType, optionType, pageable);
            case "pnl_desc" -> repository.pagePnlDesc(range.from(), range.to(), brokerAccountId, underlyingKey, contractType, optionType, pageable);
            case "pnl_asc" -> repository.pagePnlAsc(range.from(), range.to(), brokerAccountId, underlyingKey, contractType, optionType, pageable);
            default -> throw new ValidationException("sort must be one of newest, oldest, pnl_desc, pnl_asc");
        };
        return result.map(FnoTradeResponse::from);
    }

    /** The year's results; {@code fy} defaults to the current financial year. */
    public FnoSummaryResponse summary(Integer fy, UUID brokerAccountId) {
        int year = fy != null ? fy : FinancialYear.current();
        FinancialYear.Range range = FinancialYear.resolve(year, null, null);
        List<FnoTrade> trades = repository.findInWindow(range.from(), range.to(), brokerAccountId);

        Map<YearMonth, BigDecimal> months = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            months.put(YearMonth.from(range.from()).plusMonths(i), BigDecimal.ZERO);
        }
        Map<String, BigDecimal> pnlByUnderlying = new LinkedHashMap<>();
        Map<String, Long> countByUnderlying = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        long wins = 0;
        for (FnoTrade t : trades) {
            BigDecimal pnl = t.getRealizedPnl();
            total = total.add(pnl);
            charges = charges.add(t.getTotalCharges() != null ? t.getTotalCharges() : BigDecimal.ZERO);
            if (pnl.signum() > 0) {
                wins++;
            }
            months.merge(YearMonth.from(tradeDate(t)), pnl, BigDecimal::add);
            String key = underlyingOf(t);
            pnlByUnderlying.merge(key, pnl, BigDecimal::add);
            countByUnderlying.merge(key, 1L, Long::sum);
        }
        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins * 100L).divide(BigDecimal.valueOf(trades.size()), 1, RoundingMode.HALF_UP);

        List<FnoSummaryResponse.MonthPnl> byMonth = months.entrySet().stream()
                .map(e -> new FnoSummaryResponse.MonthPnl(e.getKey().toString(), e.getValue())).toList();
        List<FnoSummaryResponse.UnderlyingPnl> byUnderlying = pnlByUnderlying.entrySet().stream()
                .map(e -> new FnoSummaryResponse.UnderlyingPnl(e.getKey(), e.getValue(), countByUnderlying.get(e.getKey())))
                .sorted(Comparator.comparing((FnoSummaryResponse.UnderlyingPnl u) -> u.pnl().abs()).reversed()
                        .thenComparing(FnoSummaryResponse.UnderlyingPnl::underlying))
                .toList();
        return new FnoSummaryResponse(year, range.from(), range.to(), total, trades.size(), winRate, charges, byMonth, byUnderlying);
    }

    /** The file name suffix: the financial year, e.g. "2026-27". */
    public static String exportLabel(Integer fy) {
        return FinancialYear.label(fy != null ? fy : FinancialYear.current());
    }

    /** The year's trades for the income-tax return, oldest first, with a totals row. */
    public byte[] exportCsv(Integer fy) {
        int year = fy != null ? fy : FinancialYear.current();
        FinancialYear.Range range = FinancialYear.resolve(year, null, null);
        List<FnoTrade> trades = repository.findInWindow(range.from(), range.to(), null);

        StringBuilder out = new StringBuilder("﻿");
        out.append(CsvText.row(EXPORT_HEADER));
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal sell = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal turnover = BigDecimal.ZERO;
        for (FnoTrade t : trades) {
            BigDecimal tradeCharges = t.getTotalCharges() != null ? t.getTotalCharges() : BigDecimal.ZERO;
            BigDecimal abs = t.getRealizedPnl().abs();
            buy = buy.add(t.getBuyValue());
            sell = sell.add(t.getSellValue());
            charges = charges.add(tradeCharges);
            pnl = pnl.add(t.getRealizedPnl());
            turnover = turnover.add(abs);
            List<String> cells = new ArrayList<>();
            cells.add(tradeDate(t).toString());
            cells.add(CsvText.text(underlyingOf(t)));
            cells.add(CsvText.text(t.getTradingSymbol()));
            cells.add(plain(t.getStrikePrice()));
            cells.add(typeOf(t));
            cells.add(plain(t.getQuantity()));
            cells.add(plain(t.getBuyValue()));
            cells.add(plain(t.getSellValue()));
            cells.add(plain(tradeCharges));
            cells.add(plain(t.getRealizedPnl()));
            cells.add(plain(abs));
            out.append(CsvText.row(cells));
        }
        out.append(CsvText.row(java.util.Arrays.asList(
                "Total", null, null, null, null, null, plain(buy), plain(sell), plain(charges), plain(pnl), plain(turnover))));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static LocalDate tradeDate(FnoTrade t) {
        return t.getExitDate() != null ? t.getExitDate() : t.getEntryDate();
    }

    private static String underlyingOf(FnoTrade t) {
        return t.getUnderlyingSymbol() != null && !t.getUnderlyingSymbol().isBlank() ? t.getUnderlyingSymbol() : t.getTradingSymbol();
    }

    private static String typeOf(FnoTrade t) {
        if (t.getOptionType() != null) {
            return t.getOptionType().name();
        }
        return t.getContractType() == FnoContractType.future ? "FUT" : null;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
