package com.financeos.domain.investment.reconcile;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class GrowwCapitalGainsParser {

    private static final Logger log = LoggerFactory.getLogger(GrowwCapitalGainsParser.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record GrowwCapitalGainsExit(
            String bucket, // INTRADAY, STCG, LTCG
            String stockName,
            String isin,
            BigDecimal quantity,
            LocalDate buyDate,
            BigDecimal buyPrice,
            BigDecimal buyValue,
            LocalDate sellDate,
            BigDecimal sellPrice,
            BigDecimal sellValue,
            BigDecimal realisedPnl
    ) {}

    public List<GrowwCapitalGainsExit> parse(InputStream inputStream) {
        List<GrowwCapitalGainsExit> exits = new ArrayList<>();
        long startTimeMs = com.financeos.core.observability.ParseLogger.started(log, "GrowwCapitalGainsParser", 0, "groww-cg.xlsx");
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            String currentBucket = null;
            Map<String, Integer> headerMap = null;

            for (Row row : sheet) {
                if (row == null) continue;
                List<String> rawCellStrs = new ArrayList<>();
                for (Cell cell : row) {
                    rawCellStrs.add(getCellValueAsString(cell).trim());
                }

                // Check sub-section header
                String marker = findSubSectionMarker(rawCellStrs);
                if (marker != null) {
                    currentBucket = marker;
                    headerMap = null; // reset headers for new sub-section
                    continue;
                }

                // Check header row
                if (currentBucket != null && containsIgnoreCase(rawCellStrs, "isin") && containsIgnoreCase(rawCellStrs, "quantity")) {
                    headerMap = new HashMap<>();
                    for (int c = 0; c < rawCellStrs.size(); c++) {
                        String colName = rawCellStrs.get(c);
                        if (!colName.isBlank()) {
                            headerMap.put(colName.toLowerCase(), c);
                        }
                    }
                    continue;
                }

                // Process data row
                if (currentBucket != null && headerMap != null) {
                    String stockName = getCellByHeader(row, headerMap, "stock name");
                    String isin = getCellByHeader(row, headerMap, "isin");
                    String qtyStr = getCellByHeader(row, headerMap, "quantity");

                    if (isin == null || isin.isBlank() || isin.equalsIgnoreCase("isin")) continue;
                    if (qtyStr == null || qtyStr.isBlank()) continue;

                    BigDecimal quantity = parseDecimal(qtyStr);
                    if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) continue;

                    LocalDate buyDate = parseDate(getCellByHeader(row, headerMap, "buy date"));
                    BigDecimal buyPrice = parseDecimal(getCellByHeader(row, headerMap, "buy price"));
                    BigDecimal buyValue = parseDecimal(getCellByHeader(row, headerMap, "buy value"));

                    LocalDate sellDate = parseDate(getCellByHeader(row, headerMap, "sell date"));
                    BigDecimal sellPrice = parseDecimal(getCellByHeader(row, headerMap, "sell price"));
                    BigDecimal sellValue = parseDecimal(getCellByHeader(row, headerMap, "sell value"));

                    BigDecimal pnl = parseDecimal(getCellByHeader(row, headerMap, "realised p&l"));
                    if (pnl == null) pnl = parseDecimal(getCellByHeader(row, headerMap, "realised pnl"));

                    exits.add(new GrowwCapitalGainsExit(
                            currentBucket,
                            stockName,
                            isin.trim(),
                            quantity,
                            buyDate,
                            buyPrice,
                            buyValue,
                            sellDate,
                            sellPrice,
                            sellValue,
                            pnl != null ? pnl : BigDecimal.ZERO
                    ));
                }
            }

            com.financeos.core.observability.ParseLogger.completed(log, "GrowwCapitalGainsParser", exits.size(), "stock_name,isin,quantity,buy_date,buy_price,buy_value,sell_date,sell_price,sell_value,realised_pnl", startTimeMs);

        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "GrowwCapitalGainsParser", "extract-text", 1, "Failed to parse Groww Capital Gains report: " + e.getMessage(), e);
        }
        return exits;
    }

    private String findSubSectionMarker(List<String> rawStrs) {
        for (String s : rawStrs) {
            String lower = s.toLowerCase();
            if (lower.contains("intraday trades")) return "INTRADAY";
            if (lower.contains("short term trades")) return "STCG";
            if (lower.contains("long term trades")) return "LTCG";
            if (lower.contains("buyback trades")) return "BUYBACK";
        }
        return null;
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
            return LocalDate.parse(s, DATE_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }
}
