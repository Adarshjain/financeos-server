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
    // Page-break furniture inside a running table: the carried-forward marker ends a
    // page, a page number and the brought-forward marker start the next.
    private static final Pattern PAGE_CARRY = Pattern.compile("carried forward|balance c/f");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^page \\d+( of \\d+)?$");
    private static final Pattern PAGE_FURNITURE = Pattern.compile("^page \\d+( of \\d+)?$|brought forward|balance b/f");
    // Lines one transaction block may span after a page break (HSBC UPI/NEFT
    // narrations run 6-7 lines before the amount line).
    private static final int CARRY_WINDOW = 10;

    private static int findHeaderRow(List<Line> lines, int firstTxnIdx) {
        int stop = Math.max(firstTxnIdx - 8, -1);
        for (int i = firstTxnIdx - 1; i > stop; i--) {
            if (isHeaderLine(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isHeaderLine(Line line) {
        String text = line.text().toLowerCase(Locale.ROOT);
        int hits = 0;
        for (List<String> kws : Tokens.HEADER_KEYWORDS.values()) {
            for (String kw : kws) {
                if (text.contains(kw)) {
                    hits++;
                }
            }
        }
        return hits >= 3;
    }

    // A column header between two candidate rows, on the first one's page.
    private static boolean headerBetween(List<Line> lines, int from, int to) {
        int page = lines.get(from).page();
        for (int i = from + 1; i < to && lines.get(i).page() == page; i++) {
            if (isHeaderLine(lines.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockHasAmount(List<Line> lines, DateAnchor[] anchors, int i) {
        // An amount-bearing line closes THIS block even if it starts with a date
        // (value-date + amount + balance lines) - only an amount-less anchored
        // line starts a new block, and only when its date sits no further right
        // than this row's (a date further right is inside the narration).
        int end = Math.min(i + 7, lines.size());
        for (int j = i; j < end; j++) {
            for (Word w : lines.get(j).words()) {
                if (Tokens.parseAmount(Tokens.norm(w.text())) != null) {
                    return true;
                }
            }
            if (j > i && anchors[j] != null && ax(lines, anchors, j) <= ax(lines, anchors, i) + 15) {
                return false;
            }
        }
        return false;
    }

    private static boolean lineHasAmount(Line line) {
        return amountCount(line) > 0;
    }

    private static int amountCount(Line line) {
        int n = 0;
        for (Word w : line.words()) {
            if (Tokens.parseAmount(Tokens.norm(w.text())) != null) {
                n++;
            }
        }
        return n;
    }

    private static boolean followsAmountLine(List<Line> lines, int i) {
        for (int k = i - 1; k >= 0; k--) {
            String text = lines.get(k).text().toLowerCase(Locale.ROOT);
            if (PAGE_NUMBER.matcher(text).find()) {
                continue;
            }
            return lineHasAmount(lines.get(k));
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

        // Block layouts (HSBC): the date-column line opens a multi-line block and
        // its amounts arrive on a later line. A date outside the date column starts
        // a row only right after the previous row's amount line (indented
        // sub-transactions); one that turns up mid-block is part of the narration
        // ("2026/02/27 063232 <ref>" on NEFT rows). Left in, those out-vote the
        // real rows in the column and date-format votes, or cut a block in two.
        // A dated line with the table header right below it (ICICI's due date in
        // the summary box left of the table) is not a row and sets no column -
        // unless nothing else on its page could.
        Set<Integer> aboveHeader = new HashSet<>();
        for (int c = 0; c + 1 < cand.size(); c++) {
            if (headerBetween(lines, cand.get(c), cand.get(c + 1))) {
                aboveHeader.add(cand.get(c));
            }
        }
        Map<Integer, Double> dateColX = new LinkedHashMap<>();
        for (int i : cand) {
            if (!aboveHeader.contains(i)) {
                dateColX.merge(lines.get(i).page(), ax(lines, anchors, i), Math::min);
            }
        }
        for (int i : aboveHeader) {
            dateColX.putIfAbsent(lines.get(i).page(), ax(lines, anchors, i));
        }
        int inCol = 0;
        int inColAmountless = 0;
        for (int i : cand) {
            if (Math.abs(ax(lines, anchors, i) - dateColX.get(lines.get(i).page())) <= 15) {
                inCol++;
                if (!lineHasAmount(lines.get(i))) {
                    inColAmountless++;
                }
            }
        }
        if (inColAmountless * 2 > inCol) {
            int before = cand.size();
            cand.removeIf(i -> ax(lines, anchors, i) - dateColX.get(lines.get(i).page()) > 15
                    && !followsAmountLine(lines, i));
            if (cand.size() < before) {
                debug.accept("[debug] " + (before - cand.size()) + " narration dates inside open blocks kept as text");
            }
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

        // A dated line in a box above the transaction table (HSBC's "Your Upcoming
        // Action/Payment Dates") is not a row: it has no column header of its own,
        // and the table's header sits between it and the first real row.
        while (cand.size() >= 2 && findHeaderRow(lines, cand.get(0)) < 0
                && headerBetween(lines, cand.get(0), cand.get(1))) {
            debug.accept("[debug] dropped dated line above the table header: " + lines.get(cand.get(0)).text());
            cand.remove(0);
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
            // The block can also run across a page break (HSBC: "Balance Carried
            // Forward", page header, "Balance Brought Forward", then the rest of the
            // row and any undated same-day rows). Step over that furniture, but past
            // it only extend through amount-bearing lines so trailing text stays out.
            double dateX = ax(lines, anchors, last);
            boolean carried = false;
            int end = Math.min(last + 7, n);
            for (int j = last + 1; j < end; j++) {
                String text = lines.get(j).text().toLowerCase(Locale.ROOT);
                // A date in the date column starts a new row; one inside the narration
                // ("2026/02/27 063232 <ref>" on HSBC NEFT rows) belongs to this block.
                if (anchors[j] != null && Math.abs(ax(lines, anchors, j) - dateX) <= 15) {
                    break;
                }
                if (PAGE_CARRY.matcher(text).find()) {
                    carried = true;
                    end = Math.min(j + CARRY_WINDOW, n);
                    continue;
                }
                if (carried && PAGE_FURNITURE.matcher(text).find()) {
                    continue;
                }
                if (Tokens.OPENING_BAL.matcher(text).find() || Tokens.CLOSING_BAL.matcher(text).find()
                        || Tokens.isSummaryLine(text)
                        || text.contains("page ") || text.contains("statement") || text.contains("generated on")) {
                    break;
                }
                if (!carried) {
                    last = j;
                } else if (lineHasAmount(lines.get(j))) {
                    last = j;
                    end = Math.min(j + CARRY_WINDOW, n);
                }
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

        // Seed currentCardLast4 from statement header (document-stated number)
        String currentCardLast4 = null;
        for (int i = 0; i < first; i++) {
            String text = lines.get(i).text();
            java.util.regex.Matcher am = Tokens.ACCOUNT_LABEL.matcher(text);
            if (am.find()) {
                String last4 = extractTrailingLast4(am.group(1));
                if (last4 != null) {
                    currentCardLast4 = last4;
                    break;
                }
            }
            java.util.regex.Matcher cm = Tokens.CARD_MASK.matcher(text);
            if (cm.find()) {
                String token = cm.group(1);
                if (token.indexOf('X') >= 0 || token.indexOf('x') >= 0 || token.indexOf('*') >= 0
                        || lines.get(i).words().size() <= 6) {
                    String last4 = extractTrailingLast4(token);
                    if (last4 != null) {
                        currentCardLast4 = last4;
                        break;
                    }
                }
            }
        }

        List<RawRow> raw = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            Line ln = lines.get(i);
            if (txnSet.contains(i)) {
                raw.add(new RawRow(ln, true, anchors[i], currentCardLast4));
            } else if (!raw.isEmpty()) {
                // Dateless line inside the table: continuation of previous row -
                // unless it's a summary/furniture line (contains balance keywords).
                String text = ln.text().toLowerCase(Locale.ROOT);
                java.util.regex.Matcher cardMatcher = Tokens.CARD_MASK.matcher(ln.text());
                // A line carrying an amount and a balance is a transaction line even
                // when a reference in it looks like a masked card ("HIB- 8385506X5742925
                // 100.00 529,054.98" on HSBC card-bill payments).
                boolean hasCardMask = cardMatcher.find() && amountCount(ln) < 2;
                String cardToken = hasCardMask ? cardMatcher.group(1) : null;
                boolean isCardSectionHeader = hasCardMask && (
                        (cardToken.indexOf('X') >= 0 || cardToken.indexOf('x') >= 0 || cardToken.indexOf('*') >= 0)
                        || ln.words().size() <= 6
                );

                if (isCardSectionHeader) {
                    String extracted = extractTrailingLast4(cardToken);
                    if (extracted != null) {
                        currentCardLast4 = extracted;
                    }
                    // skip section subheader itself
                } else if (Tokens.OPENING_BAL.matcher(text).find() || Tokens.CLOSING_BAL.matcher(text).find()) {
                    raw.add(new RawRow(ln, null, null, currentCardLast4)); // marker row, keep for opening balance
                } else if (text.contains("page ") || text.contains("statement") || text.contains("generated on")
                        || Tokens.isSummaryLine(text) // totals/section boxes
                        || hasCardMask // other card mask lines
                        || PERCENT_NOTE.matcher(text).find() // interest-rate notes
                        || GSTIN_HSN.matcher(text).find()) { // invoice furniture
                    // skip
                } else {
                    raw.add(new RawRow(ln, false, null, currentCardLast4));
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

    private static String extractTrailingLast4(String raw) {
        if (raw == null) {
            return null;
        }
        String digitsOnly = raw.replaceAll("[^0-9]", "");
        if (digitsOnly.length() >= 4) {
            return digitsOnly.substring(digitsOnly.length() - 4);
        }
        return null;
    }
}
