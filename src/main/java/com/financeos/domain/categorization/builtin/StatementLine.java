package com.financeos.domain.categorization.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans a bank-statement line ("POS 4111XXXXXXXX1234 SWIGGY BLR", "UPI/412345678901/SWIGGY/swiggy@icici/Payment")
 * down to what it says about who was paid: the rails (POS, UPI, IMPS, NEFT, RTGS, ACH, NACH, ECS, BIL, INF...),
 * card and reference numbers, dates and a trailing city or country code go; a UPI line keeps its payee name and
 * its VPA handle. Pure text work, no dictionary: the categoriser matches merchants on the result.
 */
final class StatementLine {

    /** What a cleaned line holds: {@code name} and {@code handle} (UPI lines), or {@code name} alone. Both may be null. */
    record Cleaned(String name, String handle) {
        /** The form the categoriser reads a payee in: the name, then the VPA handle. */
        String asCounterparty() {
            if (name == null) {
                return handle == null ? "" : handle;
            }
            return handle == null ? name : name + " " + handle;
        }
    }

    private static final int MAX_LENGTH = 200;
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9._+\\-]+@[A-Za-z][A-Za-z0-9]*");
    /** Hyphen-separated lines: a hyphen ends the name before the VPA, so it cannot be part of the handle there. */
    private static final Pattern HANDLE_NO_HYPHEN = Pattern.compile("[A-Za-z0-9._+]+@[A-Za-z][A-Za-z0-9]*");
    private static final Pattern RAIL_START = Pattern.compile(
            "^\\s*(?:upi|upiout|imps|neft|rtgs|ach|nach|ecs|bil|inf|pos|ecom|atw|atm|nfs|mmt|ift|ib|mob|cts)\\b[\\s/\\-_:*]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD_OR_REF = Pattern.compile("\\d{4,}|\\d+x{2,}\\d*|x{2,}\\d+|\\*{2,}\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{4,}");
    private static final Pattern MASK = Pattern.compile("^(?:\\d*x{3,}\\d*|\\*{2,}\\d*|x+\\d{2,})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFSC = Pattern.compile("^[a-z]{4}0[a-z0-9]{6}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}[\\-/.]\\d{1,2}[\\-/.]\\d{2,4}\\b"
                    + "|\\b\\d{1,2}[\\s\\-/.]?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b(?:[\\s\\-/.,]?\\d{2,4}\\b)?"
                    + "|\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b",
            Pattern.CASE_INSENSITIVE);
    /** Whole-line description of the payment, not of the payee ("Payment from Phone", "UPI payment", "Sent using Paytm"). */
    private static final Pattern FILLER = Pattern.compile(
            "^(?:payment|paid|sent|pay|collect|mandate|autopay|request|txn|transaction|transfer|trf|purchase|debit|credit)"
                    + "(?:\\s+(?:from|via|on|using|to|for|by|through))?(?:\\s+(?:phone|mobile|paytm|cred|gpay|google pay|phonepe|bhim|upi|app|online|bank|net banking))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> RAILS = Set.of("upi", "upiout", "imps", "neft", "rtgs", "ach", "nach", "ecs", "bil", "inf",
            "infr", "inft", "pos", "ecom", "atw", "atm", "nfs", "mmt", "ift", "ib", "mob", "cts", "bpay", "p2a", "p2p", "p2m",
            "dr", "cr", "ref", "refno", "txn", "trxn", "attn", "vpa", "pur", "ccard", "onl");
    /** Bank and handle-bank codes that fill a statement segment. */
    private static final Set<String> BANK_CODES = Set.of("yesb", "icic", "hdfc", "sbin", "utib", "kkbk", "punb", "barb", "canb",
            "ibkl", "idib", "fdrl", "indb", "citi", "scbl", "hsbc", "bkid", "ubin", "mahb", "cbin", "ioba", "ucba", "rbis",
            "airp", "ybl", "ibl", "axl", "oksbi", "okaxis", "okhdfcbank", "okicici", "paytm", "apl", "upi", "ptys", "ptyes",
            "pthdfc", "ptsbi", "axisbank", "icici", "axis", "sbi");
    private static final Set<String> COUNTRIES = Set.of("in", "ind", "india", "us", "usa", "sg", "sgp", "gb", "gbr", "uk", "ae",
            "are", "ie", "irl", "nl", "nld", "ca", "can", "au", "aus", "de", "deu", "hk", "hkg", "fr", "fra", "ch", "che");
    private static final Set<String> CITIES = Set.of("bangalore", "bengaluru", "blr", "mumbai", "mum", "bombay", "delhi",
            "ndls", "gurgaon", "gurugram", "ggn", "noida", "hyderabad", "hyd", "chennai", "kolkata", "pune", "ahmedabad",
            "jaipur", "lucknow", "kochi", "cochin", "thane", "chandigarh", "indore", "bhopal", "surat", "vadodara",
            "coimbatore", "nagpur", "visakhapatnam", "vizag", "patna", "mysore", "mysuru", "goa", "panaji", "trivandrum",
            "thiruvananthapuram", "kanpur", "ludhiana", "nashik", "faridabad", "ghaziabad", "kozhikode", "calicut",
            "dehradun", "ranchi", "raipur", "guwahati", "bhubaneswar", "madurai", "mangalore", "navimumbai");
    private static final Pattern MULTI_CITY_TAIL = Pattern.compile("(?:new delhi|navi mumbai|greater noida)$");

    private StatementLine() {}

    /** True when the text reads like a statement line (rails, slashes or reference numbers) and not a plain name. */
    static boolean looksLike(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            return false;
        }
        return RAIL_START.matcher(raw).find() || raw.indexOf('/') >= 0 || CARD_OR_REF.matcher(raw).find()
                || DATE.matcher(raw).find();
    }

    /** The payee read out of a statement line; for text that is not one, the text with nothing taken out. */
    static Cleaned clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Cleaned(null, null);
        }
        String s = DATE.matcher(raw.trim()).replaceAll("|");
        Matcher h = (s.indexOf('/') >= 0 ? HANDLE : HANDLE_NO_HYPHEN).matcher(s);
        String handle = null;
        if (h.find()) {
            handle = h.group();
            s = s.substring(0, h.start()) + "|" + s.substring(h.end());
        }
        boolean rails = RAIL_START.matcher(s).find() || s.toLowerCase(Locale.ROOT).contains("upi/");
        // NEFT-REF-NAME and UPI-NAME-... split on hyphens too, but a plain "Coca-Cola" keeps its own
        String splitter = rails ? "[/|\\-]" : "[/|]";
        List<String> names = new ArrayList<>();
        for (String segment : s.split(splitter)) {
            String name = cleanSegment(segment);
            if (name != null) {
                names.add(name);
            }
        }
        if (handle != null) {
            // a UPI line: the first name-like segment is the payee, the rest is bank and note
            return new Cleaned(names.isEmpty() ? null : names.get(0), handle);
        }
        if (names.isEmpty()) {
            return new Cleaned(null, null);
        }
        String name = stripPlace(String.join(" ", names));
        return new Cleaned(name.isEmpty() ? null : name, null);
    }

    /** One segment without rails, numbers, dates and filler; null when nothing of a name is left. */
    private static String cleanSegment(String segment) {
        String s = segment.trim();
        if (s.isEmpty() || s.chars().noneMatch(Character::isLetter) || FILLER.matcher(s).matches() || IFSC.matcher(s).matches()
                || BANK_CODES.contains(s.toLowerCase(Locale.ROOT))) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        boolean sawRail = false;
        for (String token : s.split("[\\s_]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            // "ACH D-" / "ACH C-": the direction letter after the rail
            if (sawRail && kept.isEmpty() && (lower.equals("d") || lower.equals("c"))) {
                continue;
            }
            sawRail |= RAILS.contains(lower);
            if (RAILS.contains(lower) || MASK.matcher(token).matches() || DIGIT_RUN.matcher(token).find()
                    || IFSC.matcher(token).matches() || lower.chars().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            kept.add(token);
        }
        // a segment left as bank code plus filler ("HDFC BANK") is no payee
        if (kept.isEmpty() || (kept.size() <= 2 && kept.stream().allMatch(t -> BANK_CODES.contains(t.toLowerCase(Locale.ROOT))
                || t.equalsIgnoreCase("bank")))) {
            return null;
        }
        return String.join(" ", kept);
    }

    /** Drops a trailing city and then a trailing country code ("SWIGGY BLR IN" is "SWIGGY"), never the whole text. */
    private static String stripPlace(String name) {
        List<String> tokens = new ArrayList<>(List.of(name.split(" ")));
        boolean cut = true;
        if (tokens.stream().allMatch(t -> CITIES.contains(t.toLowerCase(Locale.ROOT)) || COUNTRIES.contains(t.toLowerCase(Locale.ROOT)))) {
            return "";
        }
        while (cut && tokens.size() > 1) {
            cut = false;
            String joined = String.join(" ", tokens).toLowerCase(Locale.ROOT);
            Matcher m = MULTI_CITY_TAIL.matcher(joined);
            if (m.find() && tokens.size() > m.group().split(" ").length) {
                for (int i = m.group().split(" ").length; i > 0; i--) {
                    tokens.remove(tokens.size() - 1);
                }
                cut = true;
                continue;
            }
            String last = tokens.get(tokens.size() - 1).toLowerCase(Locale.ROOT);
            if (CITIES.contains(last) || COUNTRIES.contains(last)) {
                tokens.remove(tokens.size() - 1);
                cut = true;
            }
        }
        return String.join(" ", tokens);
    }
}
