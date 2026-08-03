package com.financeos.domain.investment.reconcile;

import com.financeos.domain.investment.InvestmentTransactionType;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class GrowwOrderHistoryParser {

    private static final Logger log = LoggerFactory.getLogger(GrowwOrderHistoryParser.class);
    // Groww Order History stores the execution timestamp as "dd-MM-yyyy hh:mm a"
    // (e.g. "08-02-2021 02:23 PM"). Take the leading 10-char date and try dd-MM-yyyy
    // first, then fall back to ISO yyyy-MM-dd (used when the cell is a real Excel date).
    private static final DateTimeFormatter DMY_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record GrowwExecution(
            String stockName,
            String symbol,
            String isin,
            InvestmentTransactionType type,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal value,
            String exchange,
            String orderId,
            LocalDate tradeDate,
            String execTime
    ) {}

    public List<GrowwExecution> parse(InputStream inputStream) {
        List<GrowwExecution> execs = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> headerMap = null;

            for (Row row : sheet) {
                if (row == null) continue;
                List<String> rawCellStrs = new ArrayList<>();
                for (Cell cell : row) {
                    rawCellStrs.add(getCellValueAsString(cell).trim());
                }

                // Check header row. Key each column on its ABSOLUTE column index
                // (cell.getColumnIndex()) so a leading blank column can't push the compacted
                // index off-by-one — data lookups below read by absolute index via row.getCell(colIdx).
                if (headerMap == null && containsIgnoreCase(rawCellStrs, "isin") && containsIgnoreCase(rawCellStrs, "type")) {
                    headerMap = new HashMap<>();
                    for (Cell cell : row) {
                        String col = getCellValueAsString(cell).trim().toLowerCase();
                        if (!col.isBlank()) {
                            headerMap.put(col, cell.getColumnIndex());
                        }
                    }
                    continue;
                }

                // Data row
                if (headerMap != null) {
                    String status = getCellByHeader(row, headerMap, "order status");
                    if (status != null && !status.isBlank() && !status.equalsIgnoreCase("executed")) {
                        continue; // Only executed orders
                    }

                    String isin = getCellByHeader(row, headerMap, "isin");
                    String symbol = getCellByHeader(row, headerMap, "symbol");
                    String stockName = getCellByHeader(row, headerMap, "stock name");
                    String typeStr = getCellByHeader(row, headerMap, "type");
                    String exchange = getCellByHeader(row, headerMap, "exchange");
                    String orderId = getCellByHeader(row, headerMap, "exchange order id");
                    if (orderId == null || orderId.isBlank()) {
                        orderId = getCellByHeader(row, headerMap, "order id");
                    }

                    InvestmentTransactionType type = null;
                    if (typeStr != null) {
                        if (typeStr.equalsIgnoreCase("buy")) type = InvestmentTransactionType.buy;
                        else if (typeStr.equalsIgnoreCase("sell")) type = InvestmentTransactionType.sell;
                    }
                    if (type == null) continue;

                    BigDecimal qty = parseDecimal(getCellByHeader(row, headerMap, "quantity"));
                    BigDecimal totalVal = parseDecimal(getCellByHeader(row, headerMap, "value"));

                    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0 || totalVal == null || totalVal.compareTo(BigDecimal.ZERO) < 0) {
                        continue;
                    }

                    BigDecimal price = totalVal.divide(qty, 8, RoundingMode.HALF_UP);
                    String execTimeStr = getCellByHeader(row, headerMap, "execution date and time");
                    LocalDate tradeDate = parseDate(execTimeStr);

                    execs.add(new GrowwExecution(
                            stockName != null ? stockName.trim() : null,
                            symbol != null ? symbol.trim() : null,
                            isin != null ? isin.trim() : null,
                            type,
                            qty,
                            price,
                            totalVal,
                            exchange != null ? exchange.trim().toUpperCase() : "NSE",
                            orderId != null ? orderId.trim() : "",
                            tradeDate,
                            execTimeStr != null ? execTimeStr.trim() : ""
                    ));
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse Groww Order History file", e);
        }
        return execs;
    }

    private boolean containsIgnoreCase(List<String> list, String val) {
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(val));
    }

    private String getCellByHeader(Row row, Map<String, Integer> headerMap, String headerName) {
        Integer colIdx = headerMap.get(headerName.toLowerCase());
        if (colIdx == null || colIdx >= row.getLastCellNum()) return null;
        Cell cell = row.getCell(colIdx);
        return getCellValueAsString(cell);
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                } else {
                    double doubleVal = cell.getNumericCellValue();
                    if (doubleVal == Math.floor(doubleVal) && !Double.isInfinite(doubleVal)) {
                        return String.valueOf((long) doubleVal);
                    }
                    return String.valueOf(doubleVal);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private BigDecimal parseDecimal(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return new BigDecimal(str.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        String s = str.trim();
        if (s.length() >= 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s, DMY_FORMATTER);
        } catch (Exception ignored) {
            // not dd-MM-yyyy; try ISO below
        }
        try {
            return LocalDate.parse(s, ISO_FORMATTER);
        } catch (Exception e) {
            log.warn("Could not parse Groww execution date: '{}'", str);
            return null;
        }
    }
}
