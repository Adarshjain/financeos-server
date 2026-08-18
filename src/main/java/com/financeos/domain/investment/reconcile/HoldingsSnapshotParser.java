package com.financeos.domain.investment.reconcile;

import com.financeos.domain.investment.imports.ExcelReader;
import com.financeos.domain.investment.imports.SimpleCsvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@Component
public class HoldingsSnapshotParser {

    private static final Logger log = LoggerFactory.getLogger(HoldingsSnapshotParser.class);

    public record SnapshotItem(
            String isin,
            String symbol,
            BigDecimal quantity,
            BigDecimal avgCost
    ) {}

    public List<SnapshotItem> parse(InputStream inputStream, String filename) {
        List<SnapshotItem> items = new ArrayList<>();
        long startTimeMs = com.financeos.core.observability.ParseLogger.started(log, "HoldingsSnapshotParser", 0, filename);
        try {
            List<Map<String, String>> rawRows;
            if (filename != null && filename.toLowerCase().endsWith(".csv")) {
                rawRows = SimpleCsvReader.readCsv(inputStream);
            } else {
                rawRows = ExcelReader.readExcel(inputStream);
            }

            for (Map<String, String> row : rawRows) {
                String isin = getField(row, "isin");
                String symbol = getField(row, "symbol", "stock name", "scrip");
                String qtyStr = getField(row, "quantity", "qty", "holding_qty", "net_qty");
                String avgCostStr = getField(row, "average_price", "avg_price", "average price", "avg_cost", "buy_price");

                BigDecimal qty = parseDecimal(qtyStr);
                BigDecimal avgCost = parseDecimal(avgCostStr);

                if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                    items.add(new SnapshotItem(
                            isin != null ? isin.trim() : null,
                            symbol != null ? symbol.trim() : null,
                            qty,
                            avgCost
                    ));
                }
            }
            com.financeos.core.observability.ParseLogger.completed(log, "HoldingsSnapshotParser", items.size(), "isin,symbol,quantity,average_price", startTimeMs);
        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "HoldingsSnapshotParser", "extract-text", 1, "Failed to parse holdings snapshot file: " + e.getMessage(), e);
        }
        return items;
    }

    private String getField(Map<String, String> row, String... fieldNames) {
        for (String f : fieldNames) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().toLowerCase().contains(f.toLowerCase())) {
                    String val = entry.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal parseDecimal(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return new BigDecimal(str.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
