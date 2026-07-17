package com.financeos.statement.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class RowCollector {
    private RowCollector() {
    }

    // Interest-rate notes ("1.5% p.m.") and invoice furniture (GSTIN/HSN lines).
    private static final Pattern PERCENT_NOTE = Pattern.compile("\\d\\s?%");
    private static final Pattern GSTIN_HSN = Pattern.compile("\\bgstin\\b|\\bhsn\\b");

    private static int findHeaderRow(List<Line> lines, int firstTxnIdx) {
        List<String> flat = new ArrayList<>();
        for (List<String> kws : Tokens.HEADER_KEYWORDS.values()) {
            flat.addAll(kws);
        }
        int stop = Math.max(firstTxnIdx - 8, -1);
        for (int i = firstTxnIdx - 1; i > stop; i--) {
            String text = lines.get(i).text().toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String kw : flat) {
                if (text.contains(kw)) {
                    hits++;
                }
            }
            if (hits >= 3) {
                return i;
            }
        }
        return -1;
    }

    private static boolean blockHasAmount(List<Line> lines, DateAnchor[] anchors, int i) {
        // An amount-bearing line closes THIS block even if it starts with a date
        // (value-date + amount + balance lines) - only an amount-less anchored
        // line starts a new block.
        int end = Math.min(i + 7, lines.size());
        for (int j = i; j < end; j++) {
            for (Word w : lines.get(j).words()) {
                if (Tokens.parseAmount(Tokens.norm(w.text())) != null) {
                    return true;
                }
            }
            if (j > i && anchors[j] != null) {
                return false;
            }
        }
        return false;
    }

    private static double ax(List<Line> lines, DateAnchor[] anchors, int i) {
        return lines.get(i).words().get(anchors[i].start()).x0();
    }

    static CollectedRows collect(List<Line> lines, Consumer<String> debug) {
        int n = lines.size();
        DateAnchor[] anchors = new DateAnchor[n];
        for (int i = 0; i < n; i++) {
            List<Word> words = lines.get(i).words();
            anchors[i] = words.isEmpty() ? null : Dates.dateAnchor(words);
        }

        // A transaction row is date-anchored AND has an amount in its block
        // (filters out stray dates in the header zone, e.g. statement-period lines).
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (anchors[i] != null && blockHasAmount(lines, anchors, i)
                    && !Tokens.isSummaryLine(lines.get(i).text())) {
                cand.add(i);
            }
        }
        if (cand.isEmpty()) {
            throw new StatementParseException(
                    "ERROR: no date-anchored rows found — cannot locate a transaction table. "
                            + "Run with --debug and share the output.");
        }

        // Keep only rows whose date sits at a consistent x-position (the date
        // column). Clustered per page - some statements shift the table between
        // the first page and the rest.
        Set<Integer> pages = new LinkedHashSet<>();
        for (int i : cand) {
            pages.add(lines.get(i).page());
        }
        List<Integer> keptCand = new ArrayList<>();
        for (int page : pages) {
            List<Integer> pc = new ArrayList<>();
            for (int i : cand) {
                if (lines.get(i).page() == page) {
                    pc.add(i);
                }
            }
            Map<Integer, Integer> sizes = new LinkedHashMap<>();
            for (int i : pc) {
                int size = 0;
                for (int j : pc) {
                    if (Math.abs(ax(lines, anchors, j) - ax(lines, anchors, i)) <= 15) {
                        size++;
                    }
                }
                sizes.put(i, size);
            }
            int biggest = Collections.max(sizes.values());
            // Keep every column with >=2 rows (main date column plus an indented
            // sub-transaction column); singletons survive only if nothing bigger
            // exists on the page.
            for (int i : pc) {
                if (sizes.get(i) >= 2 || sizes.get(i) == biggest) {
                    keptCand.add(i);
                }
            }
        }
        cand = new ArrayList<>(keptCand);
        Collections.sort(cand);

        // Majority vote on the date format across candidates, and drop the rows
        // that don't conform (stray date-bearing lines on offer/T&C pages). This
        // must happen BEFORE the table extent is fixed - a junk candidate on a
        // late page would otherwise stretch the table over the real summary lines.
        List<String> candTexts = new ArrayList<>();
        for (int i : cand) {
            candTexts.add(anchors[i].text());
        }
        String dateFmt = Dates.inferDateFormat(candTexts);
        if (dateFmt == null) {
            throw new StatementParseException("ERROR: could not infer a date format for the transaction rows.");
        }

        List<Integer> keep = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        for (int i : cand) {
            if (Dates.parse(anchors[i].text(), dateFmt) != null) {
                keep.add(i);
            } else {
                rest.add(i);
            }
        }
        // Some statements use a second date format for indented sub-transactions
        // (e.g. "28May2026" main rows with "31MAY26" card-swipe rows). Accept a
        // secondary format if it consistently parses 2+ of the leftover rows.
        String dateFmt2 = null;
        if (rest.size() >= 2) {
            List<String> restTexts = new ArrayList<>();
            for (int i : rest) {
                restTexts.add(anchors[i].text());
            }
            String fmt2 = Dates.inferDateFormat(restTexts);
            if (fmt2 != null) {
                List<Integer> second = new ArrayList<>();
                for (int i : rest) {
                    if (Dates.parse(anchors[i].text(), fmt2) != null) {
                        second.add(i);
                    }
                }
                if (second.size() >= 2) {
                    dateFmt2 = fmt2;
                    keep.addAll(second);
                    Collections.sort(keep);
                    Set<Integer> secondSet = new HashSet<>(second);
                    rest.removeIf(secondSet::contains);
                    debug.accept("[debug] secondary date format: " + fmt2 + " (" + second.size() + " rows)");
                }
            }
        }
        if (!rest.isEmpty()) {
            List<String> restTexts = new ArrayList<>();
            for (int i : rest) {
                restTexts.add(anchors[i].text());
            }
            debug.accept("[debug] dropped " + rest.size()
                    + " date-bearing lines not matching the format(s): " + restTexts);
        }
        cand = keep;
        if (cand.isEmpty()) {
            throw new StatementParseException("ERROR: no transaction rows conform to the inferred date format.");
        }

        // Keep all remaining candidates - transaction tables can be split into
        // several blocks (per card section / per page) with summary boxes between.
        List<Integer> txnIdx = cand;
        Set<Integer> txnSet = new HashSet<>(txnIdx);

        int first = txnIdx.get(0);
        int last = txnIdx.get(txnIdx.size() - 1);
        // If the last transaction's amounts live on lines below its date line,
        // extend the table range to cover its trailing block.
        boolean lastHasAmount = false;
        for (Word w : lines.get(last).words()) {
            if (Tokens.parseAmount(Tokens.norm(w.text())) != null) {
                lastHasAmount = true;
                break;
            }
        }
        if (!lastHasAmount) {
            int end = Math.min(last + 7, n);
            for (int j = last + 1; j < end; j++) {
                if (anchors[j] != null) {
                    break;
                }
                String text = lines.get(j).text().toLowerCase(Locale.ROOT);
                if (Tokens.OPENING_BAL.matcher(text).find() || Tokens.CLOSING_BAL.matcher(text).find()
                        || Tokens.isSummaryLine(text)
                        || text.contains("page ") || text.contains("statement") || text.contains("generated on")) {
                    break;
                }
                last = j;
            }
        }
        int headerIdx = findHeaderRow(lines, first);
        int maxTxnPage = Integer.MIN_VALUE;
        for (int i : txnIdx) {
            maxTxnPage = Math.max(maxTxnPage, lines.get(i).page());
        }

        // Metadata can sit above the table AND below it (e.g. a trailing
        // "Closing Balance : ..." line), and marker rows like "Opening Balance B/F"
        // often sit between the column header and the first transaction row.
        // Trust only pages that carry transactions - trailing T&C pages contain
        // example figures next to the same labels ("balance carried forward...").
        List<Line> metaZone = new ArrayList<>();
        for (int i = 0; i < first; i++) {
            if (lines.get(i).page() <= maxTxnPage) {
                metaZone.add(lines.get(i));
            }
        }
        for (int i = last + 1; i < n; i++) {
            if (lines.get(i).page() <= maxTxnPage) {
                metaZone.add(lines.get(i));
            }
        }

        List<RawRow> raw = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            Line ln = lines.get(i);
            if (txnSet.contains(i)) {
                raw.add(new RawRow(ln, true, anchors[i]));
            } else if (!raw.isEmpty()) {
                // Dateless line inside the table: continuation of previous row -
                // unless it's a summary/furniture line (contains balance keywords).
                String text = ln.text().toLowerCase(Locale.ROOT);
                if (Tokens.OPENING_BAL.matcher(text).find() || Tokens.CLOSING_BAL.matcher(text).find()) {
                    raw.add(new RawRow(ln, null, null)); // marker row, keep for opening balance
                } else if (text.contains("page ") || text.contains("statement") || text.contains("generated on")
                        || Tokens.isSummaryLine(text) // totals/section boxes
                        || Tokens.CARD_MASK.matcher(text).find() // per-card section subheaders
                        || PERCENT_NOTE.matcher(text).find() // interest-rate notes
                        || GSTIN_HSN.matcher(text).find()) { // invoice furniture
                    // skip
                } else {
                    raw.add(new RawRow(ln, false, null));
                }
            }
        }

        long nTxn = raw.stream().filter(r -> Boolean.TRUE.equals(r.isNewTxn())).count();
        long nCont = raw.stream().filter(r -> Boolean.FALSE.equals(r.isNewTxn())).count();
        debug.accept("[debug] table region: lines " + first + ".." + last + ", "
                + nTxn + " txn rows, " + nCont + " continuation lines, header row idx: " + headerIdx);

        Line headerWords = headerIdx >= 0 ? lines.get(headerIdx) : null;
        return new CollectedRows(metaZone, raw, headerWords, dateFmt, dateFmt2, maxTxnPage);
    }
}
