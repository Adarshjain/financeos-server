package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SummaryFieldHarvester {
    private SummaryFieldHarvester() {
    }

    private record FieldLabel(String field, Pattern pattern) {
    }

    private record Span(int start, int end, Word word) {
    }

    private record MatchRec(int length, String field, int start, int end) {
    }

    private record Indexed(int li, Line line) {
    }

    // order irrelevant — overlapping matches resolved longest-first per line
    private static final List<FieldLabel> SUMMARY_FIELD_LABELS = List.of(
            new FieldLabel("total_amount_due", Pattern.compile(
                    "total\\s+(?:amount\\s+|payment\\s+)?dues?\\b|net\\s+outstanding\\s+balance"
                            + "|total\\s+outstanding\\b|outstanding\\s+balance")),
            new FieldLabel("minimum_amount_due", Pattern.compile(
                    "min(?:imum|imal)?\\s+(?:amount\\s+|payment\\s+)?due\\b")),
            new FieldLabel("payment_due_date", Pattern.compile("(?:payment\\s+)?due\\s+date")),
            new FieldLabel("credits_received", Pattern.compile("\\bcredits\\b")),
            new FieldLabel("cash_advance", Pattern.compile("cash\\s+advances?\\b")),
            new FieldLabel("fees_and_charges", Pattern.compile(
                    "other\\s+debit\\s*&?\\s*charges|fees\\s*&?\\s*(?:taxes|charges)"
                            + "|fees\\s+and\\s+charges")),
            new FieldLabel("credit_limit", Pattern.compile("(?:total\\s+)?credit\\s+limit")),
            new FieldLabel("available_credit_limit", Pattern.compile("available\\s+credit(?:\\s+limit)?")),
            new FieldLabel("available_cash_limit", Pattern.compile("available\\s+cash(?:\\s+limit)?")),
            new FieldLabel("cash_limit", Pattern.compile("\\bcash\\s+limit")),
            new FieldLabel("finance_charges", Pattern.compile("finance\\s+charges")),
            new FieldLabel("previous_balance", Pattern.compile(
                    "previous\\s+(?:statement\\s+)?(?:dues|balance)|opening\\s+balance"
                            + "|\\bopening(?=\\s+payments?\\b)")),
            new FieldLabel("payments_received", Pattern.compile(
                    "payments?\\s*[/&]?\\s*credits?\\b|payments?\\s+received"
                            + "|\\bpayments\\b(?!\\s+due)")),
            new FieldLabel("total_purchases", Pattern.compile(
                    "purchases\\s*/?\\s*(?:debits?|charges)"
                            + "|total\\s+purchases?(?:\\s+outstanding)?|\\bpurchases?\\b")),
            new FieldLabel("total_withdrawals", Pattern.compile("total\\s+withdrawals?|total\\s+debits?")),
            new FieldLabel("total_deposits", Pattern.compile("total\\s+deposits?|total\\s+credits?")),
            new FieldLabel("interest_earned", Pattern.compile("interest\\s+(?:earned|paid|credited)|int\\.?\\s*pd")),
            new FieldLabel("reward_points_balance", Pattern.compile(
                    "reward\\s+points?(?:\\s+(?:opening\\s+)?balance)?")),
            new FieldLabel("reward_points_earned", Pattern.compile("(?:points?|rewards?)\\s+earned")),
            new FieldLabel("overlimit_amount", Pattern.compile("over\\s*limit"))
    );

    private static final Pattern REWARD_LINE = Pattern.compile("points?|bonus|coins?");
    private static final Object NONE = new Object();
    private static final Pattern NIL_CELL = Pattern.compile("(?i)nil|n/?a|-{1,2}");
    // HDFC's new-layout negative dues print as "C-,278.70" (a stray comma after the minus)
    private static final Pattern STRAY_COMMA = Pattern.compile("^((?:[₹$€£C`]|Rs\\.?|INR)?-),(\\d)");

    private static String fixText(String t) {
        return STRAY_COMMA.matcher(t).replaceFirst("$1$2");
    }

    private static final Set<String> DATE_VALUED_FIELDS = Set.of("payment_due_date");
    private static final Set<String> DUES_FIELDS =
            Set.of("total_amount_due", "previous_balance", "minimum_amount_due");

    static LinkedHashMap<String, Object> harvest(List<Line> lines, Set<Line> txnLines, int maxTxnPage) {
        return harvest(lines, txnLines, maxTxnPage, new java.util.HashSet<>());
    }

    // statedNone receives the fields whose cell says "Nil" (a stated none, not a missed value).
    static LinkedHashMap<String, Object> harvest(List<Line> lines, Set<Line> txnLines, int maxTxnPage,
                                                 Set<String> statedNone) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        Set<String> explicitNone = new java.util.HashSet<>();
        boolean negativeMinimum = false;
        List<Indexed> idx = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            Line ln = lines.get(li);
            if (!txnLines.contains(ln) && ln.words().get(0).page() <= maxTxnPage) {
                idx.add(new Indexed(li, ln));
            }
        }

        for (Indexed entry : idx) {
            Line ln = entry.line();
            List<Word> words = ln.words();
            if (!labelish(words)) {
                continue;
            }
            StringBuilder textB = new StringBuilder();
            List<Span> spans = new ArrayList<>();
            for (Word w : words) {
                if (textB.length() > 0) {
                    textB.append(' ');
                }
                String token = Tokens.dedouble(w.text());
                int start = textB.length();
                spans.add(new Span(start, start + token.length(), w));
                textB.append(token);
            }
            String tl = textB.toString().toLowerCase(Locale.ROOT);
            List<MatchRec> matches = new ArrayList<>();
            for (FieldLabel label : SUMMARY_FIELD_LABELS) {
                Matcher m = label.pattern().matcher(tl);
                while (m.find()) {
                    matches.add(new MatchRec(m.end() - m.start(), label.field(), m.start(), m.end()));
                }
            }
            matches.sort((a, b) -> {
                int c = Integer.compare(b.length(), a.length());
                if (c != 0) {
                    return c;
                }
                c = b.field().compareTo(a.field());
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(b.start(), a.start());
                if (c != 0) {
                    return c;
                }
                return Integer.compare(b.end(), a.end());
            });
            List<MatchRec> chosen = new ArrayList<>();
            for (MatchRec mr : matches) {
                boolean overlap = false;
                for (MatchRec c : chosen) {
                    if (!(mr.end() <= c.start() || mr.start() >= c.end())) {
                        overlap = true;
                        break;
                    }
                }
                if (overlap) {
                    continue;
                }
                chosen.add(mr);
            }
            LinkedHashMap<String, Object> chain = chainValues(chosen, words, idx, ln);
            boolean rewardLine = REWARD_LINE.matcher(tl).find();
            for (MatchRec mr : chosen) {
                if (fields.containsKey(mr.field()) || explicitNone.contains(mr.field())) {
                    continue; // first (topmost) occurrence wins
                }
                if (rewardLine && mr.field().equals("previous_balance")) {
                    continue; // a reward-points box's "Opening Balance" is points, not money
                }
                if (chain != null && chain.containsKey(mr.field())) {
                    fields.put(mr.field(), chain.get(mr.field()));
                    continue;
                }
                List<Word> lw = new ArrayList<>();
                for (Span sp : spans) {
                    if (sp.start() < mr.end() && sp.end() > mr.start()) {
                        lw.add(sp.word());
                    }
                }
                double lx0 = lw.get(0).x0();
                double lx1 = lw.get(lw.size() - 1).x1();
                boolean wantDate = DATE_VALUED_FIELDS.contains(mr.field());
                Object val = valueRight(words, lx1, wantDate, mr.field(), idx, ln);
                if (val == null) {
                    val = valueBelow(idx, ln.page(), ln.top(), lx0, lx1, wantDate, mr.field());
                }
                if (val instanceof Double dv && mr.field().startsWith("reward_points") && dv != Math.rint(dv)) {
                    val = null; // points are whole numbers; a paise amount is a neighbour's value
                }
                if (val instanceof Double dv && dv < 0 && mr.field().equals("minimum_amount_due")) {
                    val = null; // a minimum due is never a credit
                    negativeMinimum = true;
                }
                if (val == NONE) {
                    explicitNone.add(mr.field()); // the cell says "Nil": no value, and no later prose match either
                } else if (val != null) {
                    fields.put(mr.field(), val);
                }
            }
        }
        // Nothing is owed on a credit balance, so a minimum printed as negative there means 0.
        Object total = fields.get("total_amount_due");
        if (negativeMinimum && !fields.containsKey("minimum_amount_due") && total instanceof Double t && t <= 0) {
            fields.put("minimum_amount_due", 0.0);
        }
        for (String f : explicitNone) {
            if (!fields.containsKey(f)) {
                statedNone.add(f);
            }
        }
        return fields;
    }

    // An arithmetic label row ("Previous Balance - Payments - Credits + Purchase + ... =Total
    // Payment Due") sits over a value row holding one figure per label, in the same order.
    // Nearest-by-centre picking is ambiguous when a value is wider than its label, so when the
    // row has several '-'/'+'/'=' operators and the next row carries exactly as many figures
    // as there are labels, pair them by position (rows without figures in between are skipped).
    // Anything else falls back to the geometric path.
    private static LinkedHashMap<String, Object> chainValues(List<MatchRec> chosen, List<Word> words,
                                                            List<Indexed> idx, Line ln) {
        int ops = 0;
        for (Word w : words) {
            String t = w.text();
            if (t.equals("-") || t.equals("+") || t.startsWith("=")) {
                ops++;
            }
        }
        if (ops < 3 || chosen.size() < ops) {
            return null;
        }
        List<MatchRec> ordered = new ArrayList<>(chosen);
        ordered.sort((a, b) -> Integer.compare(a.start(), b.start()));
        double top = ln.top();
        for (Indexed entry : idx) {
            List<Word> w2 = entry.line().words();
            double top2 = w2.get(0).top();
            if (w2.get(0).page() != ln.page() || !(top2 - top > 0 && top2 - top <= 30)) {
                continue;
            }
            List<Integer> amountIdx = new ArrayList<>();
            for (int i = 0; i < w2.size(); i++) {
                if (Tokens.looseAmount(w2.get(i).text()) != null) {
                    amountIdx.add(i);
                }
            }
            if (amountIdx.isEmpty()) {
                continue; // wrapped marketing text beside the value row
            }
            if (amountIdx.size() != ordered.size()) {
                return null; // first row below carrying figures is not the value row: give up
            }
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (int k = 0; k < ordered.size(); k++) {
                int wi = amountIdx.get(k);
                String field = ordered.get(k).field();
                out.putIfAbsent(field, duesSigned(w2, wi, Tokens.looseAmount(w2.get(wi).text()), field));
            }
            return out;
        }
        return null;
    }

    // Card dues carry Dr/Cr direction: Dr = owed (+), Cr = credit (−).
    private static double duesSigned(List<Word> ln2, int wIndex, double value, String field) {
        if (!DUES_FIELDS.contains(field)) {
            return value;
        }
        Word w = ln2.get(wIndex);
        String wt = fixText(w.text());
        Amount a = Tokens.parseAmount(Tokens.norm(wt));
        int s = (a != null) ? a.sign() : 0;
        // A minus (or brackets) on a dues figure marks a credit balance. Amount reports it as
        // sign -1 (a debit), which here would flip it to an owed amount.
        if (NEG_DUES.matcher(wt.strip()).find()) {
            return -value;
        }
        if (s == 0 && wIndex + 1 < ln2.size()) {
            String t = Tokens.norm(ln2.get(wIndex + 1).text()).toLowerCase(Locale.ROOT);
            s = t.equals("cr") ? 1 : t.equals("dr") ? -1 : 0;
        }
        return s == 1 ? -value : value;
    }

    private static final Pattern NEG_DUES = Pattern.compile("^\\(.*\\)$|^[^\\d]*-\\s?\\d");

    private static Object valueRight(List<Word> ln, double lx1, boolean wantDate, String field,
                                     List<Indexed> idx, Line self) {
        List<Word> right = new ArrayList<>();
        List<Integer> rightIdx = new ArrayList<>();
        for (int i = 0; i < ln.size(); i++) {
            Word w = ln.get(i);
            if (w.x0() >= lx1 - 2) {
                right.add(w);
                rightIdx.add(i);
            }
        }
        if (wantDate) {
            for (LocalDate d : Dates.findDatesInWords(right, Dates.DATE_FORMATS)) {
                if (d.getYear() > 1990) {
                    return d;
                }
            }
            return null;
        }
        boolean otherLabelSeen = false;
        boolean columnGap = false;
        double prevX1 = lx1;
        for (int j = 0; j < right.size(); j++) {
            Double v = Tokens.looseAmount(fixText(right.get(j).text()));
            if (right.get(j).x0() - prevX1 > 15) {
                columnGap = true;
            }
            prevX1 = right.get(j).x1();
            if (v == null && columnGap && right.get(j).text().endsWith(":")) {
                // a figure past another column's label ("Available Cash Limit:") is that label's; prose
                // glued to this label ("... if available: 246.04") has no column gap and does not count
                otherLabelSeen = true;
            }
            if (v != null) {
                if (otherLabelSeen) {
                    return null;
                }
                // Stacked "Label:" over value layouts: an amount sitting directly under another
                // "Label:" on the line above belongs to that label, not to this one.
                if (claimedFromAbove(idx, self, right.get(j))) {
                    return null;
                }
                return duesSigned(ln, rightIdx.get(j), v, field);
            }
        }
        return null;
    }

    private static boolean claimedFromAbove(List<Indexed> idx, Line self, Word amt) {
        for (Indexed e : idx) {
            Line o = e.line();
            double dy = self.top() - o.top();
            if (o == self || o.page() != self.page() || dy <= 0 || dy > 30) {
                continue;
            }
            List<Word> ow = o.words();
            for (int k = 0; k < ow.size(); k++) {
                Word w = ow.get(k);
                if (!w.text().endsWith(":")) {
                    continue;
                }
                // The label spans its words back to the previous label/figure ("Payment & Credits Received :"),
                // not just the colon.
                double lx0 = w.x0();
                for (int b = k - 1; b >= 0; b--) {
                    Word p = ow.get(b);
                    if (p.text().endsWith(":") || Tokens.looseAmount(fixText(p.text())) != null) {
                        break;
                    }
                    lx0 = p.x0();
                }
                if (w.x1() > amt.x0() && lx0 < amt.x1()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object valueBelow(List<Indexed> idx, int page, double top, double lx0, double lx1,
                                      boolean wantDate, String field) {
        double cx = (lx0 + lx1) / 2;
        for (Indexed entry : idx) {
            List<Word> words2 = entry.line().words();
            double top2 = words2.get(0).top();
            if (words2.get(0).page() != page || !(top2 - top > 0 && top2 - top <= 80)) {
                continue;
            }
            List<Integer> windowIdx = new ArrayList<>();
            for (int i = 0; i < words2.size(); i++) {
                Word w = words2.get(i);
                double center = (w.x0() + w.x1()) / 2;
                if (lx0 - 25 <= center && center <= lx1 + 45) {
                    windowIdx.add(i);
                }
            }
            if (wantDate) {
                List<Word> window = new ArrayList<>();
                for (int i : windowIdx) {
                    window.add(words2.get(i));
                }
                for (LocalDate d : Dates.findDatesInWords(window, Dates.DATE_FORMATS)) {
                    if (d.getYear() > 1990) {
                        return d;
                    }
                }
                // "Nil" counts only when it is the cell under this label, i.e. the word nearest its centre
                Word nearest = null;
                for (Word w : window) {
                    if (nearest == null || Math.abs((w.x0() + w.x1()) / 2 - cx) < Math.abs((nearest.x0() + nearest.x1()) / 2 - cx)) {
                        nearest = w;
                    }
                }
                if (nearest != null && NIL_CELL.matcher(Tokens.norm(nearest.text())).matches()) {
                    return NONE;
                }
                continue;
            }
            Double bestDist = null;
            Double bestVal = null;
            Integer bestIdx = null;
            for (int i : windowIdx) {
                Word w = words2.get(i);
                double wcx = (w.x0() + w.x1()) / 2;
                Double v = Tokens.looseAmount(fixText(w.text()));
                if (v != null) {
                    double dist = Math.abs(wcx - cx);
                    if (bestDist == null || dist < bestDist) {
                        bestDist = dist;
                        bestVal = v;
                        bestIdx = i;
                    }
                }
            }
            if (bestVal != null) {
                return duesSigned(words2, bestIdx, bestVal, field);
            }
        }
        return null;
    }

    // Summary labels live on short lines — or on long but Title-Case rows
    // ("Previous Balance - Payments - Credits + Purchase ..."); prose
    // sentences that merely mention a label are lowercase-heavy.
    private static boolean labelish(List<Word> ln) {
        if (ln.size() <= 20) {
            return true;
        }
        List<String> alpha = new ArrayList<>();
        for (Word w : ln) {
            String t = w.text();
            if (!t.isEmpty() && Character.isLetter(t.charAt(0))) {
                alpha.add(t);
            }
        }
        if (alpha.isEmpty()) {
            return false;
        }
        long upper = alpha.stream().filter(t -> Character.isUpperCase(t.charAt(0))).count();
        return (double) upper / alpha.size() >= 0.6;
    }
}
