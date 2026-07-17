package com.financeos.statement.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

interface WordSource {
    List<Line> extract(byte[] bytes, String password);

    static List<Line> stripRepeatedFurniture(List<Line> lines) {
        Map<String, Integer> seenOnPage = new HashMap<>();
        List<Line> kept = new ArrayList<>();
        for (Line line : lines) {
            String text = line.text();
            int page = line.page();
            Integer firstPage = seenOnPage.get(text);
            if (firstPage != null && firstPage != page) {
                continue;
            }
            seenOnPage.putIfAbsent(text, page);
            kept.add(line);
        }
        return kept;
    }
}
