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
                    headerMap = null;
                    continue;
                }

                // Header row. Key each column on its ABSOLUTE column index (cell.getColumnIndex())
                // so a leading blank column can't push the compacted index off-by-one — the data
                // lookups below read by absolute index via row.getCell(colIdx).
                if (currentBucket != null && containsIgnoreCase(rawCellStrs, "isin") && containsIgnoreCase(rawCellStrs, "quantity")) {
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
                if (currentBucket != null && headerMap != null) {
                    String isin = getCellByHeader(row, headerMap, "isin");
                    String stockName = getCellByHeader(row, headerMap, "stock name");
                    BigDecimal qty = parseDecimal(getCellByHeader(row, headerMap, "quantity"));

                    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }

                    LocalDate buyDate = parseDate(getCellByHeader(row, headerMap, "buy date"));
                    BigDecimal buyPrice = parseDecimal(getCellByHeader(row, headerMap, "buy price"));
                    BigDecimal buyValue = parseDecimal(getCellByHeader(row, headerMap, "buy value"));

                    LocalDate sellDate = parseDate(getCellByHeader(row, headerMap, "sell date"));
                    BigDecimal sellPrice = parseDecimal(getCellByHeader(row, headerMap, "sell price"));
                    BigDecimal sellValue = parseDecimal(getCellByHeader(row, headerMap, "sell value"));
                    BigDecimal pnl = parseDecimal(getCellByHeader(row, headerMap, "realised p&l"));

                    exits.add(new GrowwCapitalGainsExit(
                            currentBucket,
                            stockName != null ? stockName.trim() : null,
                            isin != null ? isin.trim() : null,
                            qty,
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

        } catch (Exception e) {
            log.error("Failed to parse Groww Capital Gains report", e);
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
