package com.financeos.statement.parser;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a statement document says about itself, read from its text lines: the issuer, whether it is a card or an
 * account statement (or terms, or a loan), its date / period, the primary and add-on card numbers, the credit limit,
 * the product phrase of its title and an explicit closure. Additive to the transaction parser: nothing here feeds
 * rows, signs or the balance oracle. Text stays in memory; callers keep only the derived fields.
 */
public final class DocumentFacts {

    private DocumentFacts() {}

    public enum DocKind { CREDIT_CARD, ACCOUNT }

    /** A number as the caller's vocabulary reads it: its last four digits and what the words before it call it. */
    public record DocNumber(String last4, boolean masked, boolean labelled, boolean cardLabel, boolean accountLabel, boolean rejected, int start) {}

    /** Domain vocabulary the document reader does not own (number labelling, placeholder numbers, closure phrases). */
    public interface Vocabulary {
        List<DocNumber> scan(String line);

        boolean placeholder(String last4);

        boolean closure(String line);
    }

    /** What the transaction parser found in the same file; used only to fill what the text rules did not. */
    public record Hints(LocalDate periodEnd, String masked4, Double limit, Double smallerLimit) {}

    /**
     * @param issuer     bank id by position and weight of evidence, or null when there is no unique dominant issuer
     * @param kind       null when the text carries no statement structure and a dated period
     * @param terms      the document's title says terms, MITC, KFS or a welcome kit
     * @param periodEnd  statement date / period end, or null
     * @param last4      the primary card or account number's last four, or null (none, or more than one primary)
     * @param limit      credit limit as printed, or null
     * @param rawProduct the product phrase of the title, or null
     * @param top        the first lines (for the caller's own catalogue lookup); in memory only
     */
    public record Facts(String issuer, DocKind kind, boolean terms, boolean loan, LocalDate periodEnd, LocalDate periodStart, String last4,
                        List<String> addOnLast4, Double limit, String rawProduct, boolean closure, List<String> top) {}

    public static final int HEADER_LINES = 40;

    static String clean(String s) {
        return s.replace("&#39;", "'").replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace('\u00a0', ' ');
    }

    /** Lines of a text, blank ones dropped. */
    static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) if (!l.isBlank()) lines.add(clean(l).trim());
        return lines;
    }

    private static final Pattern CREDIT_LIMIT = Pattern.compile(
            "(?i)(?<!available )(?<!avail\\. )(?<!cash )(?<!avail )(?<!used )(?<!utili[sz]ed )(?<!unused )(?<!remaining )credit limit\\s*(?:\\(.{0,30}\\))?\\s*[:\\-]?\\s*(?:rs\\.?|inr|₹|`|c)?\\s*([\\d,]+(?:\\.\\d{1,2})?)");
    private static final Pattern ADDON_LINE = Pattern.compile("(?i)add-?on|add on card|additional card|supplementary");
    private static final Pattern STOPWORDS = Pattern.compile("(?i)\\b(?:the|by|is|of|your|will|are|to|in|if|you|may|be|as|that|this|which|shall|with|can|any|not|has|have|from)\\b");
    private static final Pattern TITLE_TRAP = Pattern.compile(
            "(?i)terms\\s*(?:and|&)\\s*conditions|most important terms|\\bmitc\\b|key fact statement|\\bkfs\\b|welcome (?:kit|letter)|schedule of charges|key facts|cardmember agreement|fees (?:and|&) charges|offer document|rewards? (?:catalogue|program)");

    private static final Pattern CC_TOTAL = Pattern.compile("(?i)total\\s+(?:amount\\s+|payment\\s+)?dues?\\b|total\\s+amount\\s+payable|total\\s+outstanding|new\\s+balance");
    private static final Pattern CC_MIN = Pattern.compile("(?i)min(?:imum)?\\.?\\s*(?:amount|payment|amt\\.?)?\\s*due|minimum\\s+payment");
    private static final Pattern CC_LIMIT = Pattern.compile("(?i)credit\\s+limit");
    private static final Pattern CC_POINTS = Pattern.compile("(?i)reward\\s+points?|points?\\s+(?:summary|earned|balance)|cashback\\s+(?:earned|summary)|membership\\s+rewards");
    private static final Pattern CC_DUEDATE = Pattern.compile("(?i)payment\\s+due\\s+date|due\\s+date");
    private static final Pattern AC_OPEN = Pattern.compile("(?i)opening\\s+balance|balance\\s+b/?f|brought\\s+forward");
    private static final Pattern AC_CLOSE = Pattern.compile("(?i)closing\\s+balance|balance\\s+c/?f|carried\\s+forward");
    private static final Pattern AC_IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b|(?i)\\bifsc\\b|\\bmicr\\b");
    private static final Pattern AC_BRANCH = Pattern.compile("(?i)\\bbranch\\b");
    private static final Pattern AC_ACCT = Pattern.compile("(?i)(?:account|a/c)\\s*(?:no|number|#)\\.?|account\\s+type|savings\\s+account|current\\s+account");
    private static final Pattern AC_COLS = Pattern.compile("(?i)withdrawals?|deposits?|\\bdebit\\b.*\\bcredit\\b.*\\bbalance\\b");
    private static final Pattern LOAN_EMI = Pattern.compile("(?i)\\bemi\\b|principal\\s+outstanding|outstanding\\s+principal|loan\\s+account|installment|instalment");
    private static final Pattern CLOSED_STATUS = Pattern.compile(
            "(?i)(?:account|a/c|card)\\s+status\\s*[:\\-]?\\s*(?:permanently\\s+)?(?:closed|cancelled|terminated)\\b|this\\s+(?:card|account)\\s+(?:is|has been)\\s+(?:permanently\\s+)?closed");

    /**
     * A line that is itself the document's heading: up to four capitalised words (a product: "DEBIT CARD EMI", "Home"), then
     * "Loan [Account] Statement", nothing after but punctuation. Sentences that merely mention it do not match.
     */
    private static final Pattern LOAN_TITLE = Pattern.compile("(?:[A-Z][A-Za-z&\\-]*\\s+){0,4}(?i:loan\\s+(?:account\\s+)?statement)[\\s:.\\-]*");
    /** A loan number with its value: "Loan No: 0036 0825 XXXX 7708", "Loan Account Number 1234567890", "LAN: ..." (8 or more digit or mask characters). */
    private static final Pattern LOAN_NO = Pattern.compile("(?i)(?:\\bloan\\s+(?:account\\s+)?(?:no|number|#)|\\blan\\b)\\.?\\s*[:\\-]?\\s*((?:[\\dxX*]\\s?){8,})");
    private static final int LOAN_NO_LINES = 20;

    /**
     * A loan document: some short line of it is a loan-statement heading, or one of its first lines gives a loan number and no
     * other card or account number is named there. A card statement that only has an EMI / loan-on-card section (its own loan
     * numbers in a sub-table, the card number up top) is not one.
     */
    static boolean loanDocument(List<String> lines, Vocabulary vocab) {
        for (String l : lines) {
            if (LOAN_TITLE.matcher(l.trim()).matches()) return true;
        }
        Set<String> loanNumbers = new LinkedHashSet<>(), others = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(LOAN_NO_LINES, lines.size()); i++) {
            String l = lines.get(i);
            Matcher m = LOAN_NO.matcher(l);
            int from = -1, to = -1;
            if (m.find()) {
                String digits = m.group(1).replaceAll("\\s", "");
                if (digits.length() >= 4) loanNumbers.add(digits.substring(digits.length() - 4).toUpperCase(Locale.ROOT));
                from = m.start();
                to = m.end();
            }
            for (DocNumber n : vocab.scan(l)) {
                if (n.start() >= from && n.start() < to) continue;   // the loan's own number; the rest of the line still speaks
                if (!n.rejected() && !vocab.placeholder(n.last4()) && !isYear(n) && (n.masked() || n.cardLabel() || n.accountLabel())) others.add(n.last4().toUpperCase(Locale.ROOT));
            }
        }
        return !loanNumbers.isEmpty() && loanNumbers.containsAll(others);
    }

    /** A line that reads as a label or a label row with values, not a sentence of terms or marketing. */
    private static boolean structural(String line) {
        int words = line.trim().split("\\s+").length;
        if (words > 24) return false;
        Matcher m = STOPWORDS.matcher(line);
        int stop = 0;
        while (m.find()) stop++;
        return stop <= 1;
    }

    private static boolean has(List<String> lines, int limit, Pattern p) {
        for (int i = 0; i < Math.min(limit, lines.size()); i++) {
            if (p.matcher(lines.get(i)).find() && structural(lines.get(i))) return true;
        }
        return false;
    }


    public static Facts extract(String text, Vocabulary vocab, Set<String> flags, Supplier<Hints> engine) {
        return extract(linesOf(text), vocab, flags, engine);
    }

    public static Facts extract(List<String> lines, Vocabulary vocab, Set<String> flags, Supplier<Hints> engine) {
        List<String> header = lines.subList(0, Math.min(HEADER_LINES, lines.size()));

        String issuer = IssuerDetector.detect(lines, flags);

        // ---- kind: structure, never a single word
        boolean titleTrap = false;
        for (int i = 0; i < Math.min(8, lines.size()); i++) if (TITLE_TRAP.matcher(lines.get(i)).find() && !lines.get(i).toLowerCase(Locale.ROOT).contains("statement date")) titleTrap = true;
        int cardGroups = 0, acctGroups = 0, loanGroups = 0;
        int scan = 90;
        for (Pattern p : List.of(CC_TOTAL, CC_MIN, CC_LIMIT, CC_POINTS, CC_DUEDATE)) if (has(lines, scan, p)) cardGroups++;
        boolean cardCore = has(lines, scan, CC_TOTAL) || has(lines, scan, CC_MIN) || has(lines, scan, CC_LIMIT);
        for (Pattern p : List.of(AC_OPEN, AC_CLOSE, AC_IFSC, AC_BRANCH, AC_ACCT, AC_COLS)) if (has(lines, scan, p)) acctGroups++;
        boolean acctCore = has(lines, scan, AC_OPEN) || has(lines, scan, AC_CLOSE) || has(lines, scan, AC_IFSC);
        if (has(lines, scan, LOAN_EMI)) loanGroups++;
        for (Pattern p : List.of(Pattern.compile("(?i)principal"), Pattern.compile("(?i)interest\\s+rate|rate\\s+of\\s+interest"), Pattern.compile("(?i)loan\\s+(?:account\\s+)?(?:no|number)")))
            if (has(lines, scan, p)) loanGroups++;
        boolean card = cardCore && cardGroups >= 2;
        boolean account = acctCore && acctGroups >= 2;
        boolean loan = (loanGroups >= 3 && !card) || loanDocument(lines, vocab);

        // ---- date / period
        DocumentDates.Found found = DocumentDates.find(lines, 90);
        LocalDate periodEnd = found == null ? null : found.end();
        String how = found == null ? "none" : found.how();
                Hints hints = null;
        if (periodEnd == null && engine != null && (card || account) && !titleTrap) {
            hints = engine.get();
            if (hints != null && hints.periodEnd() != null) { periodEnd = hints.periodEnd(); how = "engine"; }
        }
        YearMonth period = periodEnd == null ? null : YearMonth.from(periodEnd);
        flags.add("period:" + (period != null ? how : "none"));

        DocKind kind = null;
        if (periodEnd != null && !titleTrap && !loan) kind = card ? DocKind.CREDIT_CARD : account ? DocKind.ACCOUNT : null;
        flags.add("kind:" + (kind != null ? kind : titleTrap ? "terms" : loan ? "loan" : "none"));
        flags.add("groups:card" + cardGroups + "-acct" + acctGroups);
        
        // ---- numbers: which lines speak about an add-on or a per-card subtotal (their numbers are never the primary)
        boolean[] secondary = new boolean[lines.size()];
        Set<String> addOnNumbers = new LinkedHashSet<>();
        int sectionLeft = 0;
        List<List<DocNumber>> numsByLine = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            List<DocNumber> nums = withoutBins(vocab.scan(l));
            numsByLine.add(nums);
            boolean addonLine = ADDON_LINE.matcher(l).find();
            boolean any = false;
            for (DocNumber n : nums) if (!n.rejected() && !vocab.placeholder(n.last4()) && (n.masked() || n.labelled())) any = true;
            if (addonLine && any) secondary[i] = true;
            else if (sectionLeft > 0 && any && nums.stream().anyMatch(DocNumber::masked)) secondary[i] = true;
            else if (PER_CARD.matcher(l).find() && any && l.split("\\s+").length <= 14) secondary[i] = true;
            if (addonLine && !any && structural(l)) sectionLeft = 3;
            else if (sectionLeft > 0) sectionLeft--;
        }

        // the header's own number: the single card (or, for an account, account) number in the first lines
        List<DocNumber> cardNums = new ArrayList<>(), acctNums = new ArrayList<>();
        Set<String> seenCard = new LinkedHashSet<>(), seenAcct = new LinkedHashSet<>();
        int hMasked = 0, hLabelled = 0, hPlain = 0, hRejected = 0;
        for (int i = 0; i < header.size(); i++) {
            Matcher loanNo = LOAN_NO.matcher(header.get(i));
            int loanFrom = -1, loanTo = -1;   // a loan's own number (an EMI sub-table) is no card's; the rest of its line still counts
            if (loanNo.find()) { loanFrom = loanNo.start(); loanTo = loanNo.end(); }
            for (DocNumber n : numsByLine.get(i)) {
                if (n.rejected()) { hRejected++; continue; }
                if (n.start() >= loanFrom && n.start() < loanTo) continue;
                if (n.masked()) hMasked++; else if (n.labelled()) hLabelled++; else hPlain++;
                if (vocab.placeholder(n.last4()) || secondary[i] || isYear(n)) continue;
                boolean cardLike = n.masked() || n.cardLabel();
                if (cardLike) { if (seenCard.add(n.last4())) cardNums.add(n); }
                else if (n.accountLabel()) { if (seenAcct.add(n.last4())) acctNums.add(n); }
            }
        }
        List<DocNumber> pool = kind == DocKind.ACCOUNT ? (acctNums.isEmpty() ? cardNums : acctNums) : (cardNums.isEmpty() ? (kind == null ? acctNums : List.of()) : cardNums);
        String own = pool.size() == 1 && !loan ? pool.get(0).last4() : null;   // a loan document names no card or account
        if (own == null && pool.isEmpty() && kind == DocKind.ACCOUNT) own = accountNumberBelowItsLabel(header, vocab);
        flags.add("own:" + (own != null ? "found" : pool.size() > 1 ? "ambiguous" : "none"));
        if (own == null && pool.isEmpty() && kind != null && !loan) {
            if (hints == null && engine != null) hints = engine.get();
            if (hints != null && hints.masked4() != null && !vocab.placeholder(hints.masked4())) { own = hints.masked4(); flags.add("own:engine"); }
        }
        flags.add("header-numbers:" + (hMasked > 0 ? "masked" : hLabelled > 0 ? "labelled" : hPlain > 0 ? "plain-only" : hRejected > 0 ? "rejected-only" : "none"));

        // add-ons: numbers on add-on lines and per-card subtotal rows, other than the primary (only when the primary is known for subtotals)
        Set<String> addOns = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!secondary[i]) continue;
            boolean addonish = ADDON_LINE.matcher(lines.get(i)).find() || !PER_CARD.matcher(lines.get(i)).find();
            for (DocNumber n : numsByLine.get(i)) {
                if (n.rejected() || vocab.placeholder(n.last4()) || isYear(n)) continue;
                if (!(n.masked() || n.labelled())) continue;
                if (!addonish && (own == null || !n.masked())) continue;
                addOns.add(n.last4());
            }
        }
        if (own != null) addOns.remove(own);
        for (DocNumber n : pool) if (!n.last4().equals(own)) addOns.remove(n.last4());   // a second primary in the header is not an add-on
        flags.add("addons:" + addOns.size());

        // ---- product phrase: the title/header region only (the issuer's catalogue name is the caller's)
        List<String> top = List.copyOf(header.subList(0, Math.min(15, header.size())));
        String raw = rawProduct(top);

        // ---- credit limit: summary/header region, labelled; card statements only
        Double limit = null;
        if (kind != DocKind.ACCOUNT) limit = limitIn(header);
        if (limit == null && kind == DocKind.CREDIT_CARD && engine != null && has(header, HEADER_LINES, CC_LIMIT)) {
            // a credit-limit label sits in the header but its value is in another column: the summary harvester reads columns by position
            if (hints == null) hints = engine.get();
            // the sanctioned limit is never smaller than an available / cash limit printed beside it
            if (hints != null && hints.limit() != null && hints.limit() > 0 && (hints.smallerLimit() == null || hints.limit() >= hints.smallerLimit() * 0.99)) { limit = hints.limit(); flags.add("limit:engine"); }
        }

        boolean closure = false;
        for (String l : header) {
            if (CLOSED_STATUS.matcher(l).find() || vocab.closure(l)) closure = true;
        }
        return new Facts(issuer, kind, titleTrap, loan, periodEnd, found == null ? null : found.start(), own, List.copyOf(addOns), limit, raw, closure, top);
    }

    private static final Pattern ACCT_LABEL = Pattern.compile("(?i)(?:account|a/c)\\s*(?:no|number|#)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![\\d])\\d{11,18}(?![\\d])");

    /** A row of labels naming the account number with its value in the row below: the one 11-18 digit run there (a phone number is 10). */
    private static String accountNumberBelowItsLabel(List<String> header, Vocabulary vocab) {
        for (int i = 0; i + 1 < Math.min(header.size(), 14); i++) {
            if (!ACCT_LABEL.matcher(header.get(i)).find() || header.get(i).split("\\s+").length > 14) continue;
            Matcher m = LONG_DIGITS.matcher(header.get(i + 1));
            String found = null;
            int count = 0;
            while (m.find()) { count++; found = m.group(); }
            if (count == 1 && !vocab.placeholder(found.substring(found.length() - 4))) return found.substring(found.length() - 4);
        }
        return null;
    }

    /** A per-card subtotal or summary row for a masked number: "Card ending XXXX 8735 total ...". */
    private static final Pattern PER_CARD = Pattern.compile(
            "(?i)(?:transactions?|spends?|purchases|summary|total|sub-?total)\\s+(?:for|of|on|at)\\s+(?:the\\s+)?(?:card|a/c)\\b"
                    + "|\\bcard\\b[^\\d]{0,30}(?:[x*\u2022]+[\\s-]?)+\\d{4}\\s*(?:total|sub-?total|transactions?|spends?)");

    /** "4893 21XX XXXX 2496": the leading group of a masked card number is its BIN, not a second number. */
    private static List<DocNumber> withoutBins(List<DocNumber> nums) {
        List<DocNumber> out = new ArrayList<>();
        for (DocNumber n : nums) {
            if (!n.masked() && nums.stream().anyMatch(o -> o.masked() && o.start() > n.start() && o.start() - n.start() <= 14)) continue;
            out.add(n);
        }
        return out;
    }

    /** A bare four-digit run that is a year ("ending 2026") is not a card number. */
    private static boolean isYear(DocNumber n) {
        return !n.masked() && n.last4().matches("20[0-3]\\d");
    }

    // the value after the label on one line, or in the row of values under a row of labels
    private static final Pattern LIMIT_LABELS = Pattern.compile(
            "(?i)(?:total\\s+)?credit\\s+limit|available\\s+credit(?:\\s+limit)?|available\\s+cash(?:\\s+limit)?|cash\\s+limit|total\\s+(?:payment|amount)\\s+dues?|minimum\\s+(?:payment|amount)\\s+due|payment\\s+due\\s+date|total\\s+dues?|minimum\\s+due");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:rs\\.?|inr|₹|`)?\\s?(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+\\.\\d{2})(?!\\d)");

    static Double limitIn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String l = header.get(i);
            if (l.split("\\s+").length > 18) continue;
            Matcher lm = CREDIT_LIMIT.matcher(l);
            if (lm.find()) {
                try {
                    double v = Double.parseDouble(lm.group(1).replace(",", ""));
                    if (v > 0) return v;
                } catch (NumberFormatException ignored) {
                    // no bucket
                }
            }
        }
        // a row of labels (credit limit among them) with the same number of values in the row below
        for (int i = 0; i + 1 < header.size(); i++) {
            String l = header.get(i);
            if (!CC_LIMIT.matcher(l).find() || l.split("\\s+").length > 18) continue;
            List<String> labels = new ArrayList<>();
            Matcher m = LIMIT_LABELS.matcher(l);
            while (m.find()) labels.add(m.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            int at = -1;
            for (int k = 0; k < labels.size(); k++) if (labels.get(k).equals("credit limit") || labels.get(k).equals("total credit limit")) at = k;
            if (at < 0) continue;
            List<String> values = valuesOf(header.get(i + 1));
            if (values.size() == labels.size() && values.get(at).matches("[\\d,.]+")) {
                try {
                    double v = Double.parseDouble(values.get(at).replace(",", ""));
                    if (v > 0) return v;
                } catch (NumberFormatException ignored) {
                    // no bucket
                }
            }
        }
        return null;
    }

    /** Amounts and dates of a values row, in order; dates count (so a due date keeps its column) but are not numbers. */
    private static List<String> valuesOf(String row) {
        List<String> out = new ArrayList<>();
        String rest = row;
        Matcher d = Pattern.compile("(?<![\\d/.\\-])\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}(?!\\d)|(?i)\\d{1,2}[ \\-]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[ ,\\-]+\\d{2,4}").matcher(row);
        List<int[]> spans = new ArrayList<>();
        while (d.find()) spans.add(new int[]{d.start(), d.end()});
        Matcher a = AMOUNT.matcher(row);
        List<int[]> amts = new ArrayList<>();
        List<String> amtText = new ArrayList<>();
        while (a.find()) {
            boolean inDate = false;
            for (int[] s : spans) if (a.start(1) >= s[0] && a.end(1) <= s[1]) inDate = true;
            if (!inDate) { amts.add(new int[]{a.start(1), a.end(1)}); amtText.add(a.group(1)); }
        }
        List<int[]> all = new ArrayList<>(spans);
        all.addAll(amts);
        all.sort((x, y) -> x[0] - y[0]);
        for (int[] s : all) {
            int idx = amts.indexOf(s);
            out.add(idx >= 0 ? amtText.get(idx) : "date");
        }
        return out;
    }

    private static final Pattern TITLE = Pattern.compile("(?i)^(?:your\\s+|statement\\s+for\\s+|the\\s+)?(.{2,70}?)\\s+(?:credit\\s+card|card)\\s+(?:account\\s+)?statement\\b");

    /** The product phrase as printed in the title: no digits, no punctuation beyond + & ' -; null when it names no product. */
    static String rawProduct(List<String> top) {
        for (String l : top) {
            if (ADDON_LINE.matcher(l).find()) continue;
            Matcher m = TITLE.matcher(l.trim());
            if (!m.find()) continue;
            String phrase = m.group(1).replaceAll("(?i)^(?:your|the)\\s+", "").trim();
            if (phrase.isEmpty() || !phrase.matches("[A-Za-z+&'\\- ]{2,60}") || phrase.split("\\s+").length > 6) continue;
            String core = phrase.toLowerCase(Locale.ROOT).replaceAll("\\b(?:bank|limited|ltd|credit|card|cards|the|your|of|india|first)\\b", " ").trim();
            for (String id : IssuerDetector.ids()) core = core.replace(id, " ");
            // the issuer's own name alone is not a product
            if (core.isBlank()) continue;
            if (IssuerDetector.detect(List.of(phrase), new java.util.TreeSet<>()) != null && core.split("\\s+").length <= 1
                    && IssuerDetector.detect(List.of(core), new java.util.TreeSet<>()) != null) continue;
            return phrase + " Credit Card";
        }
        return null;
    }
}
