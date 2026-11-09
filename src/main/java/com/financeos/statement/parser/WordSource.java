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
            if (firstPage != null && firstPage != page && !looksLikeTransaction(line)) {
                continue;
            }
            seenOnPage.putIfAbsent(text, page);
            kept.add(line);
        }
        return kept;
    }

    // A dated line carrying an amount is a transaction row, not running header/footer: the same
    // purchase can legitimately appear again on a later page (same date, merchant and amount).
    // Opening/closing and carried-forward lines reprinted on every page are furniture, not rows.
    java.util.regex.Pattern CARRIED_LINE = java.util.regex.Pattern.compile(
            "\\b(?:opening|closing|b/f|c/f)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static boolean looksLikeTransaction(Line line) {
        if (Tokens.SUMMARY_WORDS.matcher(line.text()).find() || CARRIED_LINE.matcher(line.text()).find()) {
            return false;
        }
        List<Word> words = line.words();
        DateAnchor anchor = Dates.dateAnchor(words);
        if (anchor == null) {
            return false;
        }
        for (int i = anchor.start() + anchor.span(); i < words.size(); i++) {
            if (Tokens.parseAmount(Tokens.norm(words.get(i).text())) != null) {
                return true;
            }
        }
        return false;
    }
}
