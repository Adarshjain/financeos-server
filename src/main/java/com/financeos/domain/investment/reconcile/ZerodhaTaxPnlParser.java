package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ItemizedChargesDto;
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
public class ZerodhaTaxPnlParser {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaTaxPnlParser.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record TaxPnlExit(
            String bucket, // INTRADAY, STCG, LTCG, BUYBACK
            String isin,
            String symbol,
            LocalDate entryDate,
            LocalDate exitDate,
            BigDecimal quantity,
            BigDecimal buyValue,
            BigDecimal sellValue,
            BigDecimal profit,
            ItemizedChargesDto charges
    ) {}

    public List<TaxPnlExit> parse(InputStream inputStream) {
        List<TaxPnlExit> exits = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet s = workbook.getSheetAt(i);
                if (s.getSheetName().toLowerCase().startsWith("tradewise exits")) {
                    sheet = s;
                    break;
                }
            }
            if (sheet == null) {
                log.warn("No sheet starting with 'Tradewise Exits' found in Zerodha Tax P&L file");
                return exits;
            }

            String currentBucket = null;
            Map<String, Integer> headerMap = null;

            for (Row row : sheet) {
                if (row == null) continue;
                List<String> cellStrs = new ArrayList<>();
                for (Cell cell : row) {
                    String val = getCellValueAsString(cell).trim();
                    if (!val.isBlank()) {
                        cellStrs.add(val);
                    }
                }

                // Check section title markers
                String marker = findSectionMarker(cellStrs);
                if (marker != null) {
                    currentBucket = getBucketForMarker(marker);
                    headerMap = null; // Reset header map for new section
                    continue;
                }

                // Check header row. Map each column header to its ABSOLUTE column index
                // (cell.getColumnIndex()), NOT its position in a compacted list — Zerodha's
                // Tradewise Exits sheet has a leading blank column A, and the POI cell iterator
                // skips physically-absent cells, so a compacted index would be off-by-one and
                // every data lookup via row.getCell(absoluteIndex) would read the wrong column.
                boolean isFnoBucket = "FNO".equalsIgnoreCase(currentBucket);
                boolean hasSymbol = containsIgnoreCase(cellStrs, "symbol");
                boolean hasIsin = containsIgnoreCase(cellStrs, "isin");
                if (currentBucket != null && hasSymbol && (hasIsin || isFnoBucket)) {
                    headerMap = new HashMap<>();
                    for (Cell cell : row) {
                        String colName = getCellValueAsString(cell).trim().toLowerCase();
                        if (!colName.isBlank()) {
                            headerMap.put(colName, cell.getColumnIndex());
                        }
                    }
                    continue;
                }

                // Data row
                if (currentBucket != null && headerMap != null) {
                    String symbol = getCellByHeader(row, headerMap, "symbol");
                    if (symbol == null || symbol.isBlank() || symbol.equalsIgnoreCase("symbol")) {
                        continue;
                    }

                    BigDecimal qty = parseDecimal(getCellByHeader(row, headerMap, "quantity"));
                    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }

                    String isin = getCellByHeader(row, headerMap, "isin");
                    LocalDate entryDate = parseDate(getCellByHeader(row, headerMap, "entry date"));
                    LocalDate exitDate = parseDate(getCellByHeader(row, headerMap, "exit date"));
                    BigDecimal buyValue = parseDecimalOrDefault(getCellByHeader(row, headerMap, "buy value"));
                    BigDecimal sellValue = parseDecimalOrDefault(getCellByHeader(row, headerMap, "sell value"));
                    BigDecimal profit = parseDecimalOrDefault(getCellByHeader(row, headerMap, "profit"));

                    // Parse Charges
                    BigDecimal brokerage = parseDecimalOrDefault(getCellByHeader(row, headerMap, "brokerage"));
                    BigDecimal exchangeTxnCharges = parseDecimalOrDefault(getCellByHeader(row, headerMap, "exchange transaction charges"));
                    BigDecimal ipft = parseDecimalOrDefault(getCellByHeader(row, headerMap, "ipft"));
                    BigDecimal sebiCharges = parseDecimalOrDefault(getCellByHeader(row, headerMap, "sebi charges"));
                    BigDecimal cgst = parseDecimalOrDefault(getCellByHeader(row, headerMap, "cgst"));
                    BigDecimal sgst = parseDecimalOrDefault(getCellByHeader(row, headerMap, "sgst"));
                    BigDecimal igst = parseDecimalOrDefault(getCellByHeader(row, headerMap, "igst"));
                    BigDecimal stampDuty = parseDecimalOrDefault(getCellByHeader(row, headerMap, "stamp duty"));
                    BigDecimal stt = parseDecimalOrDefault(getCellByHeader(row, headerMap, "stt"));

                    BigDecimal gst = cgst.add(sgst).add(igst).setScale(4, RoundingMode.HALF_UP);

                    ItemizedChargesDto charges = new ItemizedChargesDto(
                            brokerage,
                            stt,
                            exchangeTxnCharges,
                            sebiCharges,
                            stampDuty,
                            gst,
                            BigDecimal.ZERO,
                            ipft
                    );

                    exits.add(new TaxPnlExit(
                            currentBucket,
                            isin != null ? isin.trim() : null,
                            symbol.trim(),
                            entryDate,
                            exitDate,
                            qty,
                            buyValue,
                            sellValue,
                            profit,
                            charges
                    ));
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse Zerodha Tax P&L file", e);
        }
        return exits;
    }

    private String findSectionMarker(List<String> cellStrs) {
        for (String s : cellStrs) {
            String lower = s.toLowerCase();
            if (lower.contains("equity - intraday")) return "equity - intraday";
            if (lower.contains("equity - short term")) return "equity - short term";
            if (lower.contains("equity - long term")) return "equity - long term";
            if (lower.contains("equity - buyback")) return "equity - buyback";
            if (lower.contains("f&o") || lower.contains("futures") || lower.contains("derivatives") || lower.contains("equity - f&o")) return "fno";
            if (lower.contains("mutual funds") || lower.contains("currency") || lower.contains("commodity")) {
                return "NON_EQUITY";
            }
        }
        return null;
    }

    private String getBucketForMarker(String marker) {
        return switch (marker) {
            case "equity - intraday" -> "INTRADAY";
            case "equity - short term" -> "STCG";
            case "equity - long term" -> "LTCG";
            case "equity - buyback" -> "BUYBACK";
            case "fno" -> "FNO";
            default -> null; // Resets current bucket on non-equity sections!
        };
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(value));
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

    private BigDecimal parseDecimalOrDefault(String str) {
        BigDecimal val = parseDecimal(str);
        return val != null ? val : BigDecimal.ZERO;
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
