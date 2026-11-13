package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a demerger's or merger's details out of the company's own exchange notices (the short summary and the
 * attached document text): who the child or acquirer is, the share ratio and the cost split. Pure: it takes the notice
 * texts and the exchange's listing list and answers only what a notice states; a value it cannot read with certainty
 * stays blank, with the reason in {@link Reading#missing()}.
 *
 * <p>Ratios are {@code ratioFrom} (shares of the parent held) to {@code ratioTo} (shares of the child given):
 * "1 share of the child for every 10 shares of the parent" is from 10, to 1.
 */
public final class SchemeNoticeReader {

    public static final String CHILD_NOT_NAMED = "CHILD_NOT_NAMED";
    public static final String RATIO_NOT_FOUND = "RATIO_NOT_FOUND";
    public static final String COST_NOT_PUBLISHED_YET = "COST_NOT_PUBLISHED_YET";
    public static final String COST_UNCLEAR = "COST_UNCLEAR";
    public static final String NOTICE_SCANNED = "NOTICE_SCANNED";
    public static final String NOTICES_DISAGREE = "NOTICES_DISAGREE";
    public static final String NO_NOTICES = "NO_NOTICES";
    public static final String NOTICES_UNAVAILABLE = "NOTICES_UNAVAILABLE";
    /** A merger with no record date read from a sentence about the scheme itself. */
    public static final String RECORD_DATE_NOT_FOUND = "RECORD_DATE_NOT_FOUND";
    /** A merger's record date is in the future or too far from the stock's last price (set when it is about to be applied). */
    public static final String RECORD_DATE_OUT_OF_RANGE = "RECORD_DATE_OUT_OF_RANGE";

    static final int CHILD_WINDOW_DAYS = 400;
    static final int MAX_QUOTE = 300;
    private static final int MAX_RATIO_INT = 1000;
    /** How far before a ratio an excluded word (bonus, rights, ...) still makes it a different matter. */
    private static final int EXCLUDE_LOOKBACK = 200;
    private static final double SUM_TOLERANCE = 0.5;

    public enum Kind { demerger, merger }

    /** One notice: its file, date, the exchange's summary line and the text of the attached document (null when none or unreadable). */
    public record NoticeText(String url, @Nullable LocalDate date, @Nullable String summary, @Nullable String pdfText,
                             boolean scanned) {
    }

    public record Source(String url, @Nullable LocalDate date, String quote) {
    }

    public record Child(Listing listing, @Nullable Integer ratioFrom, @Nullable Integer ratioTo,
                        @Nullable Source ratioSource, @Nullable BigDecimal costPct) {
    }

    public record Reading(List<Child> children, @Nullable BigDecimal parentCostPct, @Nullable Source costSource,
                          @Nullable LocalDate recordDate, List<String> missing) {
    }

    private record Doc(NoticeText notice, String text, boolean relevant) {
    }

    private SchemeNoticeReader() {
    }

    private static final Pattern SCHEME = Pattern.compile(
            "demerg|arrangement|amalgamat|merger|\\bmerge\\b|record date|entitlement|cost of acquisition|apportion|resulting company|transferee",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_WORDS = Pattern.compile("cost of acquisition|apportion", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDED = Pattern.compile(
            "\\bbonus|rights issue|rights entitlement|\\brights\\b|sub-?division|\\bsplit|dividend|\\besop|stock option|buy-?back|debenture|warrant",
            Pattern.CASE_INSENSITIVE);
    private static final String WORDNUM = "\\(\\s*[a-z-]+(?:\\s+[a-z-]+){0,2}\\s*\\)";
    private static final String SHARE = "(?:fully\\s+paid[\\s-]*up\\s+)?(?:new\\s+)?(?:equity\\s+|ordinary\\s+)?shares?\\b";
    private static final Pattern FOR_EVERY = Pattern.compile(
            "(?<![\\d.,])(\\d{1,4})\\s*(?:" + WORDNUM + ")?\\s*" + SHARE + "(.{0,300}?)\\bfor\\s+(?:every|each)\\s+(\\d{1,4})\\s*(?:"
                    + WORDNUM + ")?\\s*(?:fully\\s+paid[\\s-]*up\\s+)?(?:equity\\s+)?shares?\\b",
            Pattern.CASE_INSENSITIVE);
    /** The other order: "for every 10 shares held in the Demerged Company, 1 share of the Resulting Company". */
    private static final Pattern EVERY_FIRST = Pattern.compile(
            "for\\s+(?:every|each)\\s+(\\d{1,4})\\s*(?:" + WORDNUM + ")?\\s*(?:fully\\s+paid[\\s-]*up\\s+)?(?:equity\\s+|ordinary\\s+)?shares?\\b"
                    + "(.{0,200}?),\\s*(\\d{1,4})\\s*(?:" + WORDNUM + ")?\\s*(?:fully\\s+paid[\\s-]*up\\s+)?(?:new\\s+)?(?:equity\\s+)?shares?\\b"
                    + "(?!.{0,150}?\\bfor\\s+(?:every|each))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITLEMENT = Pattern.compile(
            "entitlement\\s+ratio.{0,200}?(?<![\\d.,])(\\d{1,4})\\s*:\\s*(\\d{1,4})(?![\\d.,])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile(
            "(?<![\\d.])(\\d{1,3}(?:\\.\\d{1,4})?)\\s*(?:%|per\\s*cent\\b|percent\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGER_PHRASE = Pattern.compile(
            "(?:amalgamation|merger|merging|\\bmerge)\\s+of\\s+(.{3,200}?)\\s+(?:with|into)\\s+(.{3,150})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_DATE = Pattern.compile(
            "record\\s+date[^.]{0,80}?(\\d{1,2}(?:st|nd|rd|th)?[\\s\\-/.,]+[a-z]{3,9}[\\s\\-/.,]+\\d{4}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}|[a-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RECORD_DATE_AFTER = Pattern.compile(
            "(\\d{1,2}(?:st|nd|rd|th)?[\\s\\-/.,]+[a-z]{3,9}[\\s\\-/.,]+\\d{4}|[a-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})[^.]{0,30}?as\\s+(?:the\\s+)?[\"'\u201c]?record\\s+date",
            Pattern.CASE_INSENSITIVE);

    /**
     * Reads the notices of one scheme.
     *
     * @param kind         demerger (children = listings in the 400 days after the ex-date) or merger (acquirer = any listed company)
     * @param exDate       the demerger's ex-date; null for a merger
     * @param parentIsin   the parent's ISIN when known (its listing name is then also taken as the parent's name)
     * @param notices      every notice read for the parent, in any order
     * @param listings     the exchange's equity master list
     */
    public static Reading read(Kind kind, @Nullable String parentName, @Nullable String parentSymbol, @Nullable String parentIsin,
                               @Nullable LocalDate exDate, List<NoticeText> notices, List<Listing> listings) {
        return read(kind, parentName, parentSymbol, parentIsin, exDate, notices, listings, List.of());
    }

    /** One company's rename: the symbol it trades under now, the name it had and the name it has. */
    public record NameChange(String symbol, String oldName, String newName) {
    }

    /** Former names by upper-case symbol for the read in progress (set and cleared by {@link #read}). */
    private static final ThreadLocal<Map<String, List<String>>> FORMER = ThreadLocal.withInitial(Map::of);

    /**
     * As above, and a notice that names a company by a name it had before a rename ({@code nameChanges}, the exchange's
     * own list) finds the listing that carries the company today.
     */
    public static Reading read(Kind kind, @Nullable String parentName, @Nullable String parentSymbol, @Nullable String parentIsin,
                               @Nullable LocalDate exDate, List<NoticeText> notices, List<Listing> listings, List<NameChange> nameChanges) {
        Map<String, List<String>> former = new HashMap<>();
        for (NameChange c : nameChanges) {
            former.computeIfAbsent(c.symbol().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(c.oldName());
        }
        FORMER.set(former);
        try {
            return readWithFormerNames(kind, parentName, parentSymbol, parentIsin, exDate, notices, listings);
        } finally {
            FORMER.remove();
        }
    }

    /** The listing's current name and every name it had before, as one pattern. */
    static Pattern listingPattern(Listing l) {
        StringBuilder sb = new StringBuilder("(?:").append(nameRegex(l.name()));
        for (String old : FORMER.get().getOrDefault(l.symbol().trim().toUpperCase(Locale.ROOT), List.of())) {
            sb.append('|').append(nameRegex(old));
        }
        return Pattern.compile(sb.append(')').toString(), Pattern.CASE_INSENSITIVE);
    }

    private static Reading readWithFormerNames(Kind kind, @Nullable String parentName, @Nullable String parentSymbol, @Nullable String parentIsin,
                                               @Nullable LocalDate exDate, List<NoticeText> notices, List<Listing> listings) {
        List<Doc> docs = new ArrayList<>();
        for (NoticeText n : notices) {
            String text = flat((n.summary() == null ? "" : n.summary()) + "\n" + (n.pdfText() == null ? "" : n.pdfText()));
            docs.add(new Doc(n, text, SCHEME.matcher(text).find()));
        }
        docs.sort(Comparator.comparing((Doc d) -> d.notice().date(), Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Doc> relevant = docs.stream().filter(Doc::relevant).toList();

        List<String> parentNames = new ArrayList<>();
        if (parentName != null && !parentName.isBlank()) {
            parentNames.add(parentName);
        }
        for (Listing l : listings) {
            if (sameCompany(l, parentSymbol, parentIsin) && !parentNames.contains(l.name())) {
                parentNames.add(l.name());
            }
        }
        if (parentSymbol != null) {
            for (String old : FORMER.get().getOrDefault(parentSymbol.trim().toUpperCase(Locale.ROOT), List.of())) {
                if (!parentNames.contains(old)) {
                    parentNames.add(old);
                }
            }
        }

        List<Listing> found = kind == Kind.demerger
                ? findChildren(relevant, docs, listings, parentSymbol, parentIsin, exDate)
                : findAcquirer(relevant, listings, parentNames, parentSymbol, parentIsin);

        Map<Listing, Ratio> ratios = new LinkedHashMap<>();
        Set<Listing> disagree = new HashSet<>();
        Set<String> parentTokens = new HashSet<>();
        parentNames.forEach(n -> parentTokens.addAll(DemergerChildFinder.tokens(n)));
        if (parentSymbol != null) {
            parentTokens.addAll(DemergerChildFinder.tokens(parentSymbol));
        }
        readRatios(relevant, found, parentTokens, ratios, disagree);

        Cost cost = null;
        boolean costMentioned = false;
        if (kind == Kind.demerger && !found.isEmpty()) {
            costMentioned = relevant.stream().anyMatch(d -> COST_WORDS.matcher(d.text()).find());
            List<Cost> costs = new ArrayList<>();
            for (Doc d : relevant) {
                Cost c = readCost(d, parentNames, parentSymbol, found);
                if (c != null) {
                    costs.add(c);
                }
            }
            if (!costs.isEmpty()) {
                cost = costs.get(costs.size() - 1); // the latest notice that states a split
            }
        }

        List<Child> children = new ArrayList<>();
        for (Listing l : found) {
            Ratio r = ratios.get(l);
            children.add(new Child(l, r == null ? null : r.from, r == null ? null : r.to, r == null ? null : r.source,
                    cost == null ? null : cost.childPct.get(l)));
        }

        List<String> missing = new ArrayList<>();
        if (docs.isEmpty()) {
            missing.add(NO_NOTICES);
        }
        if (found.isEmpty()) {
            missing.add(CHILD_NOT_NAMED);
        }
        boolean ratioMissing = false;
        for (Child c : children) {
            ratioMissing |= c.ratioFrom() == null;
        }
        if (ratioMissing) {
            missing.add(disagree.isEmpty() ? RATIO_NOT_FOUND : NOTICES_DISAGREE);
        }
        if (kind == Kind.demerger && !found.isEmpty() && cost == null) {
            missing.add(costMentioned ? COST_UNCLEAR : COST_NOT_PUBLISHED_YET);
        }
        if (kind == Kind.merger && !found.isEmpty() && recordDate(relevant) == null) {
            missing.add(RECORD_DATE_NOT_FOUND);
        }
        boolean anyScanned = notices.stream().anyMatch(NoticeText::scanned);
        if (anyScanned && (ratioMissing || (kind == Kind.demerger && cost == null)) && !docs.isEmpty() && !found.isEmpty()) {
            missing.add(NOTICE_SCANNED);
        }
        return new Reading(children, cost == null ? null : cost.parentPct, cost == null ? null : cost.source,
                recordDate(relevant), missing);
    }

    // ---- children

    private static boolean sameCompany(Listing l, @Nullable String symbol, @Nullable String isin) {
        return (symbol != null && symbol.trim().equalsIgnoreCase(l.symbol()))
                || (isin != null && l.isin() != null && isin.trim().equalsIgnoreCase(l.isin()));
    }

    /** Listings from the day after the ex-date up to 200 days later whose full name (or former name) a relevant notice states. */
    private static List<Listing> findChildren(List<Doc> relevant, List<Doc> all, List<Listing> listings,
                                              @Nullable String parentSymbol, @Nullable String parentIsin, @Nullable LocalDate exDate) {
        List<Listing> out = new ArrayList<>();
        if (exDate == null || relevant.isEmpty()) {
            return out;
        }
        LocalDate last = exDate.plusDays(CHILD_WINDOW_DAYS);
        for (Listing l : listings) {
            if (l.listedOn() == null || !l.listedOn().isAfter(exDate) || l.listedOn().isAfter(last)
                    || sameCompany(l, parentSymbol, parentIsin)) {
                continue;
            }
            if (mentioned(relevant, listingPattern(l)) || aliasMentioned(relevant, all, l)) {
                out.add(l);
            }
        }
        out.sort(Comparator.comparing(Listing::listedOn).thenComparing(Listing::symbol));
        return out;
    }

    private static final Pattern SCHEME_WORDS = Pattern.compile(
            "demerg|resulting|arrangement|scheme|amalgamat|allot|transferee|entitle|record date", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_PROVIDER = Pattern.compile(
            "registrar|transfer\\s+agent|\\brta\\b|e-?voting|scrutini[sz]er|auditor|valuer|advisor|merchant\\s+banker|trustee|custodian|depository",
            Pattern.CASE_INSENSITIVE);

    /** True when a notice names the company as a party (not as the registrar, auditor or another service provider of the scheme). */
    private static boolean mentioned(List<Doc> docs, Pattern p) {
        for (Doc d : docs) {
            Matcher m = p.matcher(d.text());
            while (m.find()) {
                String around = d.text().substring(Math.max(0, m.start() - 100), Math.min(d.text().length(), m.end() + 100));
                String wider = d.text().substring(Math.max(0, m.start() - 300), Math.min(d.text().length(), m.end() + 300));
                if (!SERVICE_PROVIDER.matcher(around).find() && SCHEME_WORDS.matcher(wider).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "<name> (formerly known as <old name>)" in any notice, with the old name stated in a relevant one. */
    private static boolean aliasMentioned(List<Doc> relevant, List<Doc> all, Listing l) {
        Pattern former = Pattern.compile(nameRegex(l.name()) + "\\s*[(,]?\\s*(?:formerly|erstwhile|earlier|previously)(?:\\s+(?:known|called))?(?:\\s+as)?\\s+(.{3,90}?)\\s*[),.]",
                Pattern.CASE_INSENSITIVE);
        for (Doc d : all) {
            Matcher m = former.matcher(d.text());
            while (m.find()) {
                String old = m.group(1);
                if (tokenCount(old) >= 2 && mentioned(relevant, namePattern(old))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int tokenCount(String s) {
        return DemergerChildFinder.tokens(s).size();
    }

    /** The listed company named as the other side of "merger/amalgamation of <parent> with/into <X>". */
    private static List<Listing> findAcquirer(List<Doc> relevant, List<Listing> listings, List<String> parentNames,
                                              @Nullable String parentSymbol, @Nullable String parentIsin) {
        List<Pattern> parentPatterns = new ArrayList<>();
        for (String n : parentNames) {
            parentPatterns.add(namePattern(n));
        }
        String firstToken = parentNames.isEmpty() ? null : DemergerChildFinder.tokens(parentNames.get(0)).stream().findFirst().orElse(null);
        Map<Listing, Integer> votes = new HashMap<>();
        for (Doc d : relevant) {
            String t = d.text();
            Matcher m = MERGER_PHRASE.matcher(t);
            while (m.find()) {
                vote(votes, listings, parentPatterns, firstToken, parentSymbol, parentIsin, m.group(1), m.group(2));
            }
            m = AMALGAMATION_WITH.matcher(t);
            while (m.find()) {
                vote(votes, listings, parentPatterns, firstToken, parentSymbol, parentIsin,
                        t.substring(Math.max(0, m.start() - 250), m.start()), m.group(1));
            }
            m = ALLOTMENT_BY.matcher(t);
            while (m.find()) {
                vote(votes, listings, parentPatterns, firstToken, parentSymbol, parentIsin, m.group(2), m.group(1));
            }
        }
        return votes.entrySet().stream().max(Map.Entry.<Listing, Integer>comparingByValue()
                .thenComparing(e -> e.getKey().symbol())).map(e -> List.of(e.getKey())).orElse(List.of());
    }

    private static final Pattern AMALGAMATION_WITH = Pattern.compile(
            "(?:amalgamation|merger|merging|\\bmerge[sd]?)\\s+(?:with|into)\\s+(.{3,150})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOTMENT_BY = Pattern.compile(
            "allotment\\s+of\\s+shares\\s+by\\s+(.{3,120}?)\\s+to\\s+(?:the\\s+)?(?:share|stock)holders\\s+of\\s+(.{3,120})",
            Pattern.CASE_INSENSITIVE);

    /** One vote for the listed company {@code acquirerText} starts with, when {@code parentSide} speaks of the parent. */
    private static void vote(Map<Listing, Integer> votes, List<Listing> listings, List<Pattern> parentPatterns, @Nullable String firstToken,
                             @Nullable String parentSymbol, @Nullable String parentIsin, String parentSide, String acquirerText) {
        boolean parent = false;
        for (Pattern p : parentPatterns) {
            parent |= p.matcher(parentSide).find();
        }
        if (!parent && firstToken != null) {
            parent = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(firstToken) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE)
                    .matcher(parentSide).find();
        }
        if (!parent) {
            return;
        }
        String after = acquirerText.replaceFirst("(?i)^the\\s+", "");
        Listing best = null;
        int bestLen = 0;
        for (Listing l : listings) {
            if (sameCompany(l, parentSymbol, parentIsin)) {
                continue;
            }
            Matcher at = listingPattern(l).matcher(after);
            if (at.find() && at.start() == 0 && l.name().length() > bestLen) {
                best = l;
                bestLen = l.name().length();
            }
        }
        if (best != null) {
            votes.merge(best, 1, Integer::sum);
        }
    }

    // ---- ratios

    private static final class Ratio {
        int from;
        int to;
        Source source;
    }

    private record Hit(int n, int m, Doc doc, int sentenceStart, int start, int end) {
    }

    private static void readRatios(List<Doc> relevant, List<Listing> children, Set<String> parentTokens, Map<Listing, Ratio> out,
                                   Set<Listing> disagree) {
        if (children.isEmpty()) {
            return;
        }
        Map<Listing, List<Hit>> byChild = new LinkedHashMap<>();
        for (Doc d : relevant) {
            for (Hit h : ratioHits(d)) {
                Listing owner = owner(h, children);
                if (owner != null && !heldInAnotherCompany(h, parentTokens, children)) {
                    byChild.computeIfAbsent(owner, k -> new ArrayList<>()).add(h);
                }
            }
        }
        for (Map.Entry<Listing, List<Hit>> e : byChild.entrySet()) {
            List<Hit> hits = preferred(e.getKey(), e.getValue(), children.size() == 1);
            if (hits.isEmpty()) {
                continue;
            }
            Set<String> distinct = new HashSet<>();
            for (Hit h : hits) {
                distinct.add(reduced(h.n, h.m));
            }
            if (distinct.size() > 1) {
                disagree.add(e.getKey());
                continue;
            }
            Hit pick = hits.get(hits.size() - 1);
            Ratio r = new Ratio();
            r.from = pick.m;
            r.to = pick.n;
            r.source = new Source(pick.doc.notice().url(), pick.doc.notice().date(), quote(pick.doc.text(), pick.sentenceStart, pick.start, pick.end));
            out.put(e.getKey(), r);
        }
    }

    private static final Pattern OTHER_SCHEME = Pattern.compile("amalgamat|transferor|merger|swap", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULTING = Pattern.compile("resulting\\s+(?:company|entity)", Pattern.CASE_INSENSITIVE);

    /**
     * A child's hits that speak of it: those naming it or the resulting company. With a single child, when none does, the
     * rest count (a notice may call it by a short name) unless they speak of an amalgamation instead.
     */
    private static List<Hit> preferred(Listing child, List<Hit> hits, boolean single) {
        Pattern name = listingPattern(child);
        Pattern symbol = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(child.symbol()) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
        List<Hit> strong = new ArrayList<>();
        List<Hit> weak = new ArrayList<>();
        for (Hit h : hits) {
            String t = h.doc.text();
            String ctx = t.substring(Math.max(h.sentenceStart, h.start - 200), contextEnd(t, h.end));
            if (name.matcher(ctx).find() || symbol.matcher(ctx).find() || RESULTING.matcher(ctx).find()) {
                strong.add(h);
            } else if (!OTHER_SCHEME.matcher(ctx).find()) {
                weak.add(h);
            }
        }
        return !strong.isEmpty() || !single ? strong : weak;
    }

    private static final Pattern HOLDER = Pattern.compile(
            "(?:of|in)\\s+((?:[A-Z][\\w&.'()-]*\\s+){1,7}(?:Limited|Ltd\\.?|Private\\s+Limited|Pvt\\.?\\s+Ltd\\.?))");

    /**
     * "... for every 1 share of <Other Company Limited>": the shares counted are another company's (a different merger in
     * the same notice), when that name shares no word with the parent and is not a child.
     */
    private static boolean heldInAnotherCompany(Hit h, Set<String> parentTokens, List<Listing> children) {
        String t = h.doc.text();
        String after = t.substring(h.end, Math.min(t.length(), h.end + 140));
        int stop = after.indexOf(". ");
        if (stop > 0) {
            after = after.substring(0, stop);
        }
        Matcher m = HOLDER.matcher(after);
        if (!m.find()) {
            return false;
        }
        String holder = m.group(1);
        for (Listing c : children) {
            if (listingPattern(c).matcher(holder).find()) {
                return false;
            }
        }
        for (String w : DemergerChildFinder.tokens(holder)) {
            if (parentTokens.contains(w)) {
                return false;
            }
        }
        return !parentTokens.isEmpty();
    }

    private static String reduced(int n, int m) {
        int g = gcd(n, m);
        return n / g + ":" + m / g;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static List<Hit> ratioHits(Doc d) {
        List<Hit> hits = new ArrayList<>();
        String t = d.text();
        List<int[]> spans = new ArrayList<>();
        Matcher m = FOR_EVERY.matcher(t);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            addHit(hits, d, t, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), m.start(), m.end());
        }
        m = EVERY_FIRST.matcher(t);
        while (m.find()) {
            boolean overlaps = false;
            for (int[] sp : spans) {
                overlaps |= m.start() < sp[1] && sp[0] < m.end();
            }
            if (!overlaps) {
                addHit(hits, d, t, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), m.start(), m.end());
            }
        }
        m = ENTITLEMENT.matcher(t);
        while (m.find()) {
            addHit(hits, d, t, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.start(), m.end());
        }
        return hits;
    }

    private static void addHit(List<Hit> hits, Doc d, String t, int n, int m, int start, int end) {
        if (n < 1 || n > MAX_RATIO_INT || m < 1 || m > MAX_RATIO_INT) {
            return;
        }
        int s = sentenceStart(t, start);
        int e = contextEnd(t, end);
        if (EXCLUDED.matcher(t.substring(Math.max(s, start - EXCLUDE_LOOKBACK), e)).find()) {
            return;
        }
        hits.add(new Hit(n, m, d, s, start, end));
    }

    /** The child a ratio sentence is about: the only child, else the one named closest to the ratio. */
    private static Listing owner(Hit h, List<Listing> children) {
        if (children.size() == 1) {
            return children.get(0);
        }
        String ctx = h.doc.text().substring(h.sentenceStart, contextEnd(h.doc.text(), h.end));
        int offset = h.start - h.sentenceStart;
        Listing best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Listing c : children) {
            Matcher m = listingPattern(c).matcher(ctx);
            while (m.find()) {
                int dist = Math.abs(m.start() - offset);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
        }
        return best;
    }

    /** Start of the sentence holding {@code idx}: after the last ". " that does not end an abbreviation. */
    static int sentenceStart(String t, int idx) {
        int i = idx;
        while (i > 1) {
            if ((t.charAt(i - 1) == ' ' && t.charAt(i - 2) == '.') || t.charAt(i - 1) == ';') {
                int w = i - 2;
                while (w > 0 && Character.isLetter(t.charAt(w - 1))) {
                    w--;
                }
                String word = t.substring(w, Math.max(w, i - 2)).toLowerCase(Locale.ROOT);
                if (!(word.equals("ltd") || word.equals("pvt") || word.equals("no") || word.equals("co") || word.equals("rs")
                        || word.equals("nos") || word.equals("inc") || word.equals("corp") || word.length() <= 1)) {
                    return i;
                }
            }
            i--;
        }
        return 0;
    }

    /** End of the sentence holding {@code idx}, at most 60 characters on. */
    private static int contextEnd(String t, int idx) {
        int limit = Math.min(t.length(), idx + 60);
        int stop = t.indexOf(". ", idx);
        return stop >= 0 && stop < limit ? stop + 1 : limit;
    }

    private static String quote(String t, int sentenceStart, int start, int end) {
        int a = Math.max(sentenceStart, start - 20);
        int e = Math.min(t.length(), end + 30);
        if (e - a > MAX_QUOTE) {
            a = e - MAX_QUOTE;
        }
        return wholeWords(t, a, e);
    }

    /** The text from a to e without a word cut at either end. */
    private static String wholeWords(String t, int a, int e) {
        while (a > 0 && a < e && t.charAt(a - 1) != ' ' && t.charAt(a) != ' ') {
            a++;
        }
        while (e < t.length() && e > a && t.charAt(e) != ' ' && t.charAt(e - 1) != ' ') {
            e--;
        }
        return t.substring(a, e).trim();
    }

    // ---- cost split

    private static final class Cost {
        BigDecimal parentPct;
        Map<Listing, BigDecimal> childPct = new HashMap<>();
        Source source;
    }

    private record Mention(int entity, int start, int end) {
    }

    /**
     * The split in a notice that speaks of the cost of acquisition: every entity (the parent and each child) must get
     * exactly one percentage, from the one that follows its name (or precedes it), and all must add up to 100.
     */
    private static Cost readCost(Doc d, List<String> parentNames, @Nullable String parentSymbol, List<Listing> children) {
        String t = d.text();
        Matcher kw = COST_WORDS.matcher(t);
        int lastTried = -10_000;
        while (kw.find()) {
            if (kw.start() - lastTried < 800) {
                continue;
            }
            lastTried = kw.start();
            int from = Math.max(0, kw.start() - 300);
            int to = Math.min(t.length(), kw.start() + 2500);
            String region = t.substring(from, to);
            Cost c = costInRegion(region, parentNames, parentSymbol, children);
            if (c != null) {
                c.source = new Source(d.notice().url(), d.notice().date(), costQuote(region, c));
                return c;
            }
        }
        return null;
    }

    private static String costQuote(String region, Cost c) {
        Matcher m = PERCENT.matcher(region);
        int first = -1;
        int last = -1;
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            if (matches(c, v)) {
                if (first < 0) {
                    first = m.start();
                }
                last = m.end();
            }
        }
        if (first < 0) {
            return wholeWords(region, 0, Math.min(region.length(), MAX_QUOTE));
        }
        int a = Math.max(0, first - 120);
        int e = Math.min(region.length(), Math.max(last + 30, a + 1));
        if (e - a > MAX_QUOTE) {
            a = Math.max(0, e - MAX_QUOTE);
        }
        return wholeWords(region, a, e);
    }

    private static boolean matches(Cost c, double v) {
        if (Math.abs(c.parentPct.doubleValue() - v) < 0.0001) {
            return true;
        }
        return c.childPct.values().stream().anyMatch(b -> Math.abs(b.doubleValue() - v) < 0.0001);
    }

    private static final Pattern BARE_PERCENT = Pattern.compile("[\\s:|\\-]{0,6}(\\d{1,2}\\.\\d{1,4})(?![\\d.,%])");

    private static Cost costInRegion(String region, List<String> parentNames, @Nullable String parentSymbol, List<Listing> children) {
        int entities = children.size() + 1;
        List<Mention> mentions = new ArrayList<>();
        Set<String> childNames = new HashSet<>();
        for (Listing c : children) {
            childNames.add(norm(c.name()));
        }
        for (String n : parentNames) {
            if (!childNames.contains(norm(n))) {
                addMentions(mentions, 0, namePattern(n), region);
            }
        }
        addMentions(mentions, 0, Pattern.compile("(?<![a-z0-9])(?:demerged|transferor)\\s+(?:company|entity|undertaking)", Pattern.CASE_INSENSITIVE), region);
        for (int i = 0; i < children.size(); i++) {
            addMentions(mentions, i + 1, listingPattern(children.get(i)), region);
            if (children.size() == 1) {
                addMentions(mentions, 1, Pattern.compile("(?<![a-z0-9])resulting\\s+company", Pattern.CASE_INSENSITIVE), region);
            }
        }
        mentions = dropOverlaps(mentions);

        // every percentage, plus a bare "56.10" standing right after a name (a table cell)
        TreeMap<Integer, Double> found = new TreeMap<>();
        Matcher pm = PERCENT.matcher(region);
        while (pm.find()) {
            found.put(pm.start(), Double.parseDouble(pm.group(1)));
        }
        for (Mention mn : mentions) {
            Matcher bm = BARE_PERCENT.matcher(region);
            bm.region(mn.end, Math.min(region.length(), mn.end + 12));
            if (bm.lookingAt()) {
                found.putIfAbsent(bm.start(1), Double.parseDouble(bm.group(1)));
            }
        }
        if (found.size() < entities) {
            return null;
        }
        List<Integer> at = new ArrayList<>(found.keySet());
        List<Double> pcts = new ArrayList<>(found.values());

        // 1) the percentage that follows (or precedes) each name; both ways must agree when both read
        double[] following = nearest(mentions, at, pcts, entities, true, region.length());
        double[] preceding = nearest(mentions, at, pcts, entities, false, region.length());
        double[] v = null;
        if (acceptable(following) && acceptable(preceding)) {
            v = same(following, preceding) ? following : null;
        } else if (acceptable(following)) {
            v = following;
        } else if (acceptable(preceding)) {
            v = preceding;
        } else {
            // 2) a column layout (names first, then the percentages in the same order); every reading of it must agree
            double[] first = null;
            boolean clash = false;
            for (int k = 0; k + entities <= pcts.size(); k++) {
                double[] w = tableWindow(mentions, at, pcts, entities, k);
                if (acceptable(w)) {
                    if (first == null) {
                        first = w;
                    } else if (!same(first, w)) {
                        clash = true;
                    }
                }
            }
            v = clash ? null : first;
        }
        if (v == null) {
            return null;
        }
        Cost c = new Cost();
        c.parentPct = BigDecimal.valueOf(v[0]);
        for (int i = 0; i < children.size(); i++) {
            c.childPct.put(children.get(i), BigDecimal.valueOf(v[i + 1]));
        }
        return c;
    }

    /** Each entity gets the first percentage that follows a mention of it (or the last one that precedes it). Null when one is missing. */
    private static double[] nearest(List<Mention> mentions, List<Integer> at, List<Double> pcts, int entities, boolean following,
                                    int regionLength) {
        double[] value = new double[entities];
        boolean[] set = new boolean[entities];
        for (int k = 0; k < mentions.size(); k++) {
            Mention mn = mentions.get(k);
            int lo = following ? mn.end : (k == 0 ? 0 : mentions.get(k - 1).end);
            int hi = following ? (k + 1 < mentions.size() ? mentions.get(k + 1).start : regionLength) : mn.start;
            Double pick = null;
            for (int j = 0; j < pcts.size(); j++) {
                int p = at.get(j);
                if (p >= lo && p < hi && (following ? p - mn.end : mn.start - p) <= 250) {
                    pick = pcts.get(j);
                    if (following) {
                        break;
                    }
                }
            }
            if (pick != null && !set[mn.entity]) {
                value[mn.entity] = pick;
                set[mn.entity] = true;
            }
        }
        for (boolean b : set) {
            if (!b) {
                return null;
            }
        }
        return value;
    }

    /** A column layout: the names (each entity once, in order) come first, then the percentages in that order. */
    private static double[] tableWindow(List<Mention> mentions, List<Integer> at, List<Double> pcts, int entities, int k) {
        int gapStart = k == 0 ? 0 : at.get(k - 1);
        List<Mention> gap = new ArrayList<>();
        for (Mention mn : mentions) {
            if (mn.start >= gapStart && mn.end <= at.get(k)) {
                gap.add(mn);
            }
        }
        List<Mention> runs = new ArrayList<>(); // a name followed by its own label ("X Limited (Resulting Company)") counts once
        for (Mention mn : gap) {
            if (runs.isEmpty() || runs.get(runs.size() - 1).entity != mn.entity) {
                runs.add(mn);
            }
        }
        if (runs.size() < entities) {
            return null;
        }
        List<Mention> last = runs.subList(runs.size() - entities, runs.size());
        Set<Integer> distinct = new HashSet<>();
        for (Mention mn : last) {
            distinct.add(mn.entity);
        }
        if (distinct.size() != entities) {
            return null;
        }
        double[] value = new double[entities];
        for (int i = 0; i < entities; i++) {
            value[last.get(i).entity] = pcts.get(k + i);
        }
        return value;
    }

    private static boolean same(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 0.005) {
                return false;
            }
        }
        return true;
    }

    private static boolean acceptable(double[] v) {
        if (v == null) {
            return false;
        }
        double sum = 0;
        for (double x : v) {
            if (x <= 0 || x >= 100) {
                return false;
            }
            sum += x;
        }
        return Math.abs(sum - 100) <= SUM_TOLERANCE;
    }

    private static void addMentions(List<Mention> out, int entity, Pattern p, String text) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(new Mention(entity, m.start(), m.end()));
        }
    }

    /** Sorted by position; a mention inside a longer one (another entity's longer name) is dropped. */
    private static List<Mention> dropOverlaps(List<Mention> all) {
        List<Mention> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(Mention::start).thenComparing(m -> -(m.end - m.start)));
        List<Mention> out = new ArrayList<>();
        for (Mention m : sorted) {
            if (!out.isEmpty() && m.start < out.get(out.size() - 1).end) {
                Mention prev = out.get(out.size() - 1);
                if (m.end - m.start > prev.end - prev.start) {
                    out.set(out.size() - 1, m);
                }
                continue;
            }
            out.add(m);
        }
        return out;
    }

    // ---- record date

    /** A sentence a record date may come from must say it is about the scheme's share allotment. */
    private static final Pattern DATE_PURPOSE = Pattern.compile(
            "merger|amalgamat|scheme|allot|share\\s+exchange|entitle", Pattern.CASE_INSENSITIVE);
    /** A sentence about voting, a meeting, a dividend or the AGM sets a different record or cut-off date. */
    private static final Pattern DATE_NOT_PURPOSE = Pattern.compile(
            "e-?voting|remote\\s+voting|cut-?off\\s+date|\\bmeeting|dividend|\\bagm\\b|annual\\s+general|\\begm\\b|extra-?ordinary\\s+general|postal\\s+ballot",
            Pattern.CASE_INSENSITIVE);

    /**
     * The first record date, latest notice first, taken only from a sentence whose purpose is the scheme (merger, amalgamation,
     * scheme, allotment, share exchange, entitlement) and not a vote, meeting, dividend or AGM; null when there is none.
     */
    @Nullable
    static LocalDate recordDate(List<Doc> relevant) {
        for (int i = relevant.size() - 1; i >= 0; i--) {
            String text = relevant.get(i).text();
            for (Pattern p : new Pattern[] {RECORD_DATE, RECORD_DATE_AFTER}) {
                Matcher m = p.matcher(text);
                while (m.find()) {
                    LocalDate d = parseDate(m.group(1));
                    if (d != null && purposeIsScheme(sentenceAround(text, m.start(), m.end()))) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    private static boolean purposeIsScheme(String sentence) {
        return DATE_PURPOSE.matcher(sentence).find() && !DATE_NOT_PURPOSE.matcher(sentence).find();
    }

    /** The sentence (at most 400 characters either side) holding [start, end): bounded by a full stop and space, a semicolon or the text's ends. */
    private static String sentenceAround(String text, int start, int end) {
        int from = Math.max(0, start - 400);
        int lo = from;
        Matcher before = Pattern.compile("[.;]\\s+(?=[A-Z(\\d])|;").matcher(text.substring(from, start));
        while (before.find()) {
            lo = from + before.end();
        }
        int limit = Math.min(text.length(), end + 400);
        int hi = limit;
        Matcher after = Pattern.compile("\\.\\s+(?=[A-Z])|;").matcher(text.substring(end, limit));
        if (after.find()) {
            hi = end + after.start();
        }
        return text.substring(lo, hi);
    }

    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** "07-Jun-2022", "7th June 2022", "June 7, 2022" or "07/06/2022" (day first); null when it is not a date. */
    @Nullable
    static LocalDate parseDate(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("(\\d)(?:st|nd|rd|th)\\b", "$1").replaceAll("[\\s\\-/.,]+", " ").trim();
        String[] p = s.split(" ");
        try {
            if (p.length != 3) {
                return null;
            }
            int day;
            int month;
            int year;
            if (p[0].matches("\\d{1,2}") && p[1].matches("[a-z]{3,9}")) {
                day = Integer.parseInt(p[0]);
                month = MONTHS.indexOf(p[1].substring(0, 3)) + 1;
                year = Integer.parseInt(p[2]);
            } else if (p[0].matches("[a-z]{3,9}") && p[1].matches("\\d{1,2}")) {
                day = Integer.parseInt(p[1]);
                month = MONTHS.indexOf(p[0].substring(0, 3)) + 1;
                year = Integer.parseInt(p[2]);
            } else if (p[0].matches("\\d{1,2}") && p[1].matches("\\d{1,2}")) {
                day = Integer.parseInt(p[0]);
                month = Integer.parseInt(p[1]);
                year = Integer.parseInt(p[2]);
            } else {
                return null;
            }
            return month < 1 ? null : LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- text helpers

    /** Whitespace collapsed, typographic quotes straightened. */
    static String flat(String s) {
        return s.replace(' ', ' ').replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
                .replaceAll("\\s+", " ").trim();
    }

    /** Lower-case words only, "ltd" read as "limited"; two names are the same company name when these are equal. */
    static String norm(String name) {
        return String.join(" ", words(name));
    }

    private static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        for (String w : name.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("['’]", "").replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            out.add(w.equals("ltd") ? "limited" : w.equals("pvt") ? "private" : w);
        }
        return out;
    }

    /** The company name as a phrase: same words in order (limited/ltd, private/pvt alike), any light punctuation between, whole words only. */
    static String nameRegex(String name) {
        StringBuilder sb = new StringBuilder("(?<![a-z0-9])");
        boolean first = true;
        for (String w : words(name)) {
            if (!first) {
                sb.append("[\\s.,()\\-/'&]{1,4}");
            }
            first = false;
            sb.append(switch (w) {
                case "limited" -> "(?:limited|ltd)";
                case "private" -> "(?:private|pvt)";
                case "and" -> "(?:and|&)";
                // "Wall's" is "walls" once apostrophes are dropped: allow it back before a final s
                default -> w.length() > 2 && w.endsWith("s") ? Pattern.quote(w.substring(0, w.length() - 1)) + "'?s" : Pattern.quote(w);
            });
        }
        return sb.append("(?![a-z0-9])").toString();
    }

    static Pattern namePattern(String name) {
        return Pattern.compile(nameRegex(name), Pattern.CASE_INSENSITIVE);
    }
}
