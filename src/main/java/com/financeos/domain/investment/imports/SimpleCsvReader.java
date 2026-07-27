package com.financeos.domain.investment.imports;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SimpleCsvReader {

    public static List<Map<String, String>> readCsv(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        List<List<String>> rows = new ArrayList<>();
        
        String line;
        StringBuilder currentToken = new StringBuilder();
        List<String> currentRow = new ArrayList<>();
        boolean inQuotes = false;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("\uFEFF")) { // Remove UTF-8 BOM if present
                line = line.substring(1);
            }

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        currentToken.append('"');
                        i++; // Skip escaped quote
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (c == ',' && !inQuotes) {
                    currentRow.add(currentToken.toString().trim());
                    currentToken.setLength(0);
                } else {
                    currentToken.append(c);
                }
            }

            if (inQuotes) {
                currentToken.append("\n"); // Multi-line quote
            } else {
                currentRow.add(currentToken.toString().trim());
                currentToken.setLength(0);
                if (!currentRow.isEmpty()) {
                    rows.add(new ArrayList<>(currentRow));
                }
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            currentRow.add(currentToken.toString().trim());
            rows.add(currentRow);
        }

        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> headers = rows.get(0).stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .toList();

        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.isEmpty() || (row.size() == 1 && row.get(0).isBlank())) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (int col = 0; col < headers.size(); col++) {
                String val = col < row.size() ? row.get(col) : "";
                map.put(headers.get(col), val);
            }
            result.add(map);
        }

        return result;
    }
}
