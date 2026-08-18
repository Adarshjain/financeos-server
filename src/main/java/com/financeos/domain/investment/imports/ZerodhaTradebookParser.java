package com.financeos.domain.investment.imports;

import com.financeos.domain.investment.InvestmentTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class ZerodhaTradebookParser implements ImportParser {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaTradebookParser.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public ImportSource source() {
        return ImportSource.zerodha_tradebook;
    }

    @Override
    public List<ParsedRow> parse(InputStream inputStream, ParseContext context) {
        List<ParsedRow> parsedRows = new ArrayList<>();
        long startTimeMs = com.financeos.core.observability.ParseLogger.started(log, "ZerodhaTradebookParser", 0, "zerodha-tradebook.csv");
        try {
            List<Map<String, String>> rawRows = SimpleCsvReader.readCsv(inputStream);

            for (int i = 0; i < rawRows.size(); i++) {
                int rowIndex = i + 1;
                Map<String, String> row = rawRows.get(i);

                String symbol = getField(row, "symbol");
                String isin = getField(row, "isin");
                String tradeDateStr = getField(row, "trade_date");
                String exchange = getField(row, "exchange");
                String segment = getField(row, "segment");
                String tradeTypeStr = getField(row, "trade_type");
                String quantityStr = getField(row, "quantity");
                String priceStr = getField(row, "price");
                String tradeId = getField(row, "trade_id");
                String orderId = getField(row, "order_id");

                String error = null;

                // Validate segment
                if (segment != null && !segment.isBlank()) {
                    String segLower = segment.toLowerCase();
                    if (!segLower.equals("eq") && !segLower.equals("equity")) {
                        error = "Unsupported segment: " + segment + " (only Equity tradebook supported)";
                    }
                }

                InvestmentTransactionType type = null;
                if (error == null) {
                    if (tradeTypeStr != null && !tradeTypeStr.isBlank()) {
                        if (tradeTypeStr.equalsIgnoreCase("buy")) {
                            type = InvestmentTransactionType.buy;
                        } else if (tradeTypeStr.equalsIgnoreCase("sell")) {
                            type = InvestmentTransactionType.sell;
                        } else {
                            error = "Unknown trade_type: " + tradeTypeStr;
                        }
                    } else {
                        error = "Missing trade_type";
                    }
                }

                BigDecimal quantity = null;
                if (error == null) {
                    try {
                        quantity = new BigDecimal(quantityStr);
                        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                            error = "Quantity must be positive: " + quantityStr;
                        }
                    } catch (Exception e) {
                        error = "Invalid quantity: " + quantityStr;
                    }
                }

                BigDecimal price = null;
                if (error == null) {
                    try {
                        price = new BigDecimal(priceStr);
                        if (price.compareTo(BigDecimal.ZERO) < 0) {
                            error = "Price cannot be negative: " + priceStr;
                        }
                    } catch (Exception e) {
                        error = "Invalid price: " + priceStr;
                    }
                }

                LocalDate tradeDate = null;
                if (error == null) {
                    try {
                        tradeDate = LocalDate.parse(tradeDateStr);
                    } catch (Exception e) {
                        error = "Invalid trade_date format (expected YYYY-MM-DD): " + tradeDateStr;
                    }
                }

                String externalRef = null;
                if (tradeId != null && !tradeId.isBlank()) {
                    externalRef = "zerodha_trade_" + tradeId.trim();
                } else if (orderId != null && !orderId.isBlank()) {
                    externalRef = "zerodha_order_" + orderId.trim();
                }

                ParsedRow parsedRow = new ParsedRow(
                        rowIndex,
                        "trade",
                        type,
                        symbol != null ? symbol.trim() : null,
                        isin != null && !isin.isBlank() ? isin.trim() : null,
                        symbol != null ? symbol.trim() : null,
                        exchange != null && !exchange.isBlank() ? exchange.trim().toUpperCase() : "NSE",
                        quantity,
                        price,
                        null,
                        tradeDate,
                        null, // charges not in tradebook
                        externalRef,
                        row,
                        error
                );

                parsedRows.add(parsedRow);
                if (error != null) {
                    com.financeos.core.observability.ParseLogger.rejectedRow(log, "ZerodhaTradebookParser", rowIndex, error);
                }
            }

            com.financeos.core.observability.ParseLogger.completed(log, "ZerodhaTradebookParser", parsedRows.size(), "symbol,isin,trade_date,exchange,segment,trade_type,quantity,price,trade_id,order_id", startTimeMs);

        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "ZerodhaTradebookParser", "extract-text", 1, "Failed to parse Zerodha tradebook CSV: " + e.getMessage(), e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, null,
                    null, null, null, null, null, null,
                    Collections.emptyMap(), "Failed to read CSV file: " + e.getMessage()
            ));
        }

        return parsedRows;
    }

    private String getField(Map<String, String> row, String fieldName) {
        String val = row.get(fieldName.toLowerCase());
        return val != null ? val.trim() : null;
    }
}
