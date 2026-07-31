package com.financeos.domain.investment.imports;

import com.financeos.domain.investment.InvestmentTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

@Component
public class GrowwStocksParser implements ImportParser {

    private static final Logger log = LoggerFactory.getLogger(GrowwStocksParser.class);

    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DMY_DASH_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MM-yyyy").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter DMY_SLASH_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd/MM/yyyy").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter DMY_MON_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);

    @Override
    public ImportSource source() {
        return ImportSource.groww;
    }

    @Override
    public List<ParsedRow> parse(InputStream inputStream, ParseContext context) {
        List<ParsedRow> parsedRows = new ArrayList<>();
        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to read Groww input stream", e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "NSE",
                    null, null, null, null, null, Collections.emptyMap(),
                    "Failed to read file: " + e.getMessage()
            ));
            return parsedRows;
        }

        List<Map<String, String>> rawRows;
        try {
            if (isXlsx(bytes)) {
                rawRows = ExcelReader.readExcel(new ByteArrayInputStream(bytes), List.of("isin", "quantity", "type"));
            } else {
                rawRows = SimpleCsvReader.readCsv(new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            log.error("Failed to parse Groww file", e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "NSE",
                    null, null, null, null, null, Collections.emptyMap(),
                    "Failed to parse CSV/XLSX export: " + e.getMessage()
            ));
            return parsedRows;
        }

        for (int i = 0; i < rawRows.size(); i++) {
            int rowIndex = i + 1;
            Map<String, String> row = rawRows.get(i);

            String symbol = findAlias(row, "symbol", "stock name", "stock", "company", "instrument", "name");
            String name = findAlias(row, "stock name", "company name", "scrip name");
            String isin = findAlias(row, "isin");
            String typeStr = findAlias(row, "type", "buy/sell", "action", "order type", "transaction type");
            String quantityStr = findAlias(row, "quantity", "qty", "shares", "units", "no of shares");
            String priceStr = findAlias(row, "price", "avg price", "execution price", "price per share", "rate");
            String valueStr = findAlias(row, "value", "amount", "order value", "net amount", "traded value", "total value");
            String tradeDateStr = findAlias(row, "execution date and time", "execution date", "trade date", "order date", "transaction date", "date", "time");
            String orderId = findAlias(row, "order id", "reference", "trade id", "order no", "ref no", "exchange order id");
            String exchangeStr = findAlias(row, "exchange");
            String categoryStr = findAlias(row, "category", "segment", "asset class");
            String orderStatusStr = findAlias(row, "order status", "status");

            String error = null;

            // Order status filter check
            if (orderStatusStr != null && !orderStatusStr.isBlank() && !orderStatusStr.trim().equalsIgnoreCase("Executed")) {
                error = "Order not executed (status: " + orderStatusStr.trim() + ")";
            }

            // Mutual Fund exclusion check
            if (error == null && ((isin != null && isin.toUpperCase().startsWith("INF"))
                    || (categoryStr != null && categoryStr.toLowerCase().contains("mutual fund"))
                    || (symbol != null && (symbol.toLowerCase().contains("mutual fund") || symbol.toLowerCase().contains("scheme"))))) {
                error = "Groww Mutual Fund transaction detected — Mutual Funds must be imported via CAMS/KFintech CAS (Phase 4b) to avoid double counting.";
            }

            InvestmentTransactionType type = null;
            if (error == null) {
                if (typeStr != null && !typeStr.isBlank()) {
                    String t = typeStr.toLowerCase();
                    if (t.contains("buy") || t.equals("b") || t.contains("purchase")) {
                        type = InvestmentTransactionType.buy;
                    } else if (t.contains("sell") || t.equals("s") || t.contains("redemption")) {
                        type = InvestmentTransactionType.sell;
                    } else {
                        error = "Invalid transaction type: " + typeStr;
                    }
                } else {
                    error = "Missing transaction type";
                }
            }

            BigDecimal quantity = null;
            if (error == null) {
                if (quantityStr != null && !quantityStr.isBlank()) {
                    try {
                        quantity = new BigDecimal(quantityStr.replace(",", ""));
                        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                            error = "Quantity must be positive: " + quantityStr;
                        }
                    } catch (Exception e) {
                        error = "Invalid quantity: " + quantityStr;
                    }
                } else {
                    error = "Missing quantity";
                }
            }

            BigDecimal price = null;
            if (error == null) {
                if (priceStr != null && !priceStr.isBlank()) {
                    try {
                        price = new BigDecimal(priceStr.replace(",", ""));
                        if (price.compareTo(BigDecimal.ZERO) < 0) {
                            error = "Price must be non-negative: " + priceStr;
                        }
                    } catch (Exception e) {
                        error = "Invalid price: " + priceStr;
                    }
                } else if (valueStr != null && !valueStr.isBlank()) {
                    if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
                        try {
                            BigDecimal value = new BigDecimal(valueStr.replace(",", ""));
                            if (value.compareTo(BigDecimal.ZERO) < 0) {
                                error = "Value must be non-negative: " + valueStr;
                            } else {
                                price = value.divide(quantity, 4, java.math.RoundingMode.HALF_UP);
                                if (price.compareTo(BigDecimal.ZERO) < 0) {
                                    error = "Price must be non-negative: " + price;
                                }
                            }
                        } catch (Exception e) {
                            error = "Invalid value: " + valueStr;
                        }
                    } else {
                        error = "Missing price/value";
                    }
                } else {
                    error = "Missing price/value";
                }
            }

            LocalDate tradeDate = null;
            if (error == null) {
                if (tradeDateStr != null && !tradeDateStr.isBlank()) {
                    tradeDate = parseDate(tradeDateStr);
                    if (tradeDate == null) {
                        error = "Invalid trade date format: " + tradeDateStr;
                    }
                } else {
                    error = "Missing trade date";
                }
            }

            if (error == null && (symbol == null || symbol.isBlank())) {
                error = "Missing symbol/stock name";
            }

            String exchange = exchangeStr != null && !exchangeStr.isBlank() ? exchangeStr.trim().toUpperCase() : "NSE";

            ParsedRow parsedRow = new ParsedRow(
                    rowIndex,
                    "trade",
                    type,
                    symbol != null ? symbol.trim() : null,
                    isin != null && !isin.isBlank() ? isin.trim() : null,
                    name != null && !name.isBlank() ? name.trim() : (symbol != null ? symbol.trim() : null),
                    exchange,
                    quantity,
                    price,
                    tradeDate,
                    null, // Groww stock order history export has no itemized charges
                    orderId != null && !orderId.isBlank() ? orderId.trim() : null,
                    row,
                    error
            );

            parsedRows.add(parsedRow);
        }

        return parsedRows;
    }

    private boolean isXlsx(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    private String findAlias(Map<String, String> row, String... aliases) {
        // Pass 1: Exact normalized header match
        for (String alias : aliases) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(alias.trim())) {
                    String val = entry.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }

        // Pass 2: fuzzy contains-match ONLY for unambiguous multi-word aliases
        // (those containing a space). A generic single word like "type"/"name"
        // must never substring-steal a longer unintended column — a miss is a
        // safe, visible "missing field" error, whereas a wrong match silently
        // corrupts holdings. Single-word aliases therefore rely on Pass 1 (exact).
        for (String alias : aliases) {
            String a = alias.trim().toLowerCase();
            if (!a.contains(" ")) {
                continue; // single word → exact-match only (Pass 1)
            }
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.toLowerCase().contains(a)) {
                    String val = entry.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }
        return null;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String s = dateStr.trim();
        if (s.contains("T")) {
            s = s.split("T")[0];
        } else if (s.contains(" ")) {
            s = s.split(" ")[0];
        }
        try { return LocalDate.parse(s, ISO_DATE_FORMATTER); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DMY_DASH_FORMATTER); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DMY_SLASH_FORMATTER); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DMY_MON_FORMATTER); } catch (Exception ignored) {}
        return null;
    }
}
