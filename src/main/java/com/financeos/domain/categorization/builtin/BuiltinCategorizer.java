package com.financeos.domain.categorization.builtin;

import com.financeos.gmail.extract.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, rule-based categoriser that runs before the AI one. Steps, first hit wins: LINK (legs of a money
 * move, no category), KEYWORD (phrases that decide alone), MERCHANT (dictionary of well-known brands), MCC,
 * PEOPLE (counterparty is an individual). An empty result means "leave it to the AI". Plain class: the YAML
 * under {@code categorize/} is read once in the constructor.
 */
public final class BuiltinCategorizer {

    /** Keyword and link rules look at this much of the email text: past it, footers and marketing take over. */
    private static final int TEXT_CHARS = 1500;
    /** Counterparties some banks cut at about 11 characters still match a longer name they are the start of. */
    private static final int TRUNCATED_MIN = 9;
    private static final int COMPACT_MIN = 5;
    /** Run-together handles ("zerodhabroking@..") match a dictionary name by prefix only when it is long enough to be unlikely inside a word. */
    private static final int COMPACT_PREFIX_MIN = 8;

    private static final Pattern JUNK_COUNTERPARTY = Pattern.compile(
            "\\b(?:confirm that|inform you|your (?:credit|debit) card|email address|dear customer|you have)\\b", Pattern.CASE_INSENSITIVE);
    /** Letters, spaces, apostrophes, hyphens and dots between initials: no digits, '*' or domain-style dots. */
    private static final Pattern PLAIN_NAME = Pattern.compile("^(?:[\\p{L}' \\-]++|\\.(?=\\s|$))++$");
    private static final Pattern UPI_PREFIX = Pattern.compile("^upi[_\\-\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOUR_DIGITS = Pattern.compile("\\d{4}");
    private static final Pattern NOT_LETTER = Pattern.compile("[^a-z]");
    private static final Pattern HANDLE_SEPARATORS = Pattern.compile("[._\\-]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** Longer counterparties are not a name or merchant: ignored rather than scanned. */
    private static final int MAX_COUNTERPARTY = 200;
    private static final Pattern HANDLE = Pattern.compile("^([^\\s@]+)@([a-z0-9]+)", Pattern.CASE_INSENSITIVE);
    /** Some mails lose the '@': "name okicici". */
    private static final Pattern SPACED_HANDLE = Pattern.compile("^([a-z][a-z0-9.]*)(?: \\d)? (ok(?:icici|axis|hdfcbank|sbi))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE_HANDLE = Pattern.compile("^(?:\\+?(?:91)?\\d{10}(?:-\\d)?|\\*+[a-z0-9]{2,4})$",
            Pattern.CASE_INSENSITIVE);
    /** Personal (not business) UPI ids: a Google Pay "ok" handle, or a PhonePe/BHIM numeric id. */
    private static final Pattern CONSUMER_LOCAL = Pattern.compile("^[a-z][a-z0-9.]*(?:-\\d+)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSUMER_BANK = Pattern.compile("^(?:ok(?!biz)[a-z]+|ybl|ibl|axl)$", Pattern.CASE_INSENSITIVE);
    private static final int BUSINESS_SUBSTRING_MIN = 5;
    /** Handle banks of business UPI ids ("okbizaxis"): never a person. */
    private static final Pattern BUSINESS_BANK = Pattern.compile("^okbiz", Pattern.CASE_INSENSITIVE);
    /** Merchant QR / code-style local parts ("q" + digits, "sv" + digits): not a person's id. */
    private static final Pattern CODE_LOCAL = Pattern.compile("^(?:q|sv|paytmqr|bharatpe)[a-z]*\\d{6,}", Pattern.CASE_INSENSITIVE);
    /** A transfer: UPI, IMPS, NEFT, RTGS, a VPA, or the bank's person-to-person / person-to-account tag. */
    private static final Pattern TRANSFER = Pattern.compile("\\b(?:upi|imps|neft|rtgs|vpa|p2p|p2a)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P2P = Pattern.compile("\\bp2[pa]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("\\b(?:credit|debit) card\\b|\\bcard (?:no|ending)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_CARD = Pattern.compile("\\bcredit card\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE_WITH_APP = Pattern.compile("^\\d{10}\\s+[a-z]+$", Pattern.CASE_INSENSITIVE);
    /** "Dear Asha Rao," / "Hi Asha,": how mails greet the account holder. */
    private static final Pattern GREETING = Pattern.compile(
            "\\b(?:dear|hi|hey|hello)\\s*,?\\s+([a-z][a-z.]*(?: [a-z][a-z.]*){0,3})\\s*[,!|]", Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> GREETING_STOP = java.util.Set.of("customer", "cardholder", "card", "holder",
            "member", "valued", "sir", "madam", "user", "client", "there", "all", "team", "sir/madam");
    /** Where the email itself names the payee when the extracted counterparty is missing or not a name. */
    private static final List<Pattern> TEXT_PAYEE = List.of(
            Pattern.compile("\\btowards VPA (?<handle>\\S+@\\S+) \\((?<name>[^)]{1,60})\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSender: (?<name>.{2,60}?) \\(VPA: (?<handle>\\S+?)\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfor payment to (?<name>.{2,45}?) on \\d", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bInfo: (?<name>.{2,45}?)\\.(?:\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwas paid on (?<name>[a-z][a-z.]{2,30})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bat (?<name>.{2,45}?) on \\d{1,2}[-/ ]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btowards (?<name>.{2,50}?) (?:with|on|ref)\\b", Pattern.CASE_INSENSITIVE));

    private final BuiltinData data;

    public BuiltinCategorizer() {
        this.data = BuiltinData.load();
    }

    /** Decides a category for one transaction, or returns empty when nothing here is sure enough. */
    public Optional<BuiltinMatch> categorize(CategorizeInput in) {
        String text = in.text() == null ? "" : in.text().length() > TEXT_CHARS ? in.text().substring(0, TEXT_CHARS) : in.text();
        Payee payee = payee(in, text);
        boolean transfer = TRANSFER.matcher(text).find();
        boolean cardSpend = !transfer && CARD.matcher(text).find();
        List<String> holder = holder(in, text);
        if (isSelf(payee, holder)) {
            return Optional.of(new BuiltinMatch(null, Step.LINK, "self-name"));
        }

        for (BuiltinData.Rule r : data.link) {
            if (matches(r, in, text, payee)) {
                return Optional.of(new BuiltinMatch(null, Step.LINK, r.id()));
            }
        }
        for (BuiltinData.Rule r : data.keywords) {
            if (matches(r, in, text, payee)) {
                return Optional.of(new BuiltinMatch(r.category(), Step.KEYWORD, r.id()));
            }
        }
        for (String name : payee.names) {
            BuiltinData.Merchant m = merchant(name);
            // a credit from a merchant is a refund, cashback or something else: the AI reads the mail for that
            if (m != null && in.direction() == Direction.CREDIT) {
                return Optional.empty();
            }
            if (m != null) {
                return Optional.of(new BuiltinMatch(m.category(), Step.MERCHANT, m.name()));
            }
        }
        String byMcc = mcc(in.mcc());
        if (byMcc != null) {
            return Optional.of(new BuiltinMatch(byMcc, Step.MCC, in.mcc().trim()));
        }
        if (!cardSpend && !CREDIT_CARD.matcher(text).find() && isPerson(payee, P2P.matcher(text).find())) {
            return Optional.of(new BuiltinMatch("People", Step.PEOPLE, payee.personalHandle ? "handle" : "name"));
        }
        return Optional.empty();
    }

    private boolean matches(BuiltinData.Rule r, CategorizeInput in, String text, Payee payee) {
        if (r.direction() != null && r.direction() != in.direction()) {
            return false;
        }
        if (r.maxAmount() != null && (in.amount() == null || in.amount().compareTo(r.maxAmount()) > 0)) {
            return false;
        }
        String subject = nullToEmpty(in.subject());
        String counterparty = payee.display;
        return anyHit(r.subject(), subject) && anyHit(r.text(), text) && anyHit(r.counterparty(), counterparty)
                && anyHit(r.from(), nullToEmpty(in.fromAddress()))
                && anyHit(r.any(), subject + " | " + counterparty + " | " + text);
    }

    /** An absent condition (empty list) always holds; otherwise one of its regexes must be found. */
    private static boolean anyHit(List<Pattern> patterns, String value) {
        if (patterns.isEmpty()) {
            return true;
        }
        for (Pattern p : patterns) {
            if (p.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Longest dictionary name the counterparty equals or starts with as whole words; failing that, the one
     * dictionary family a cut-off counterparty is the start of (several categories: no answer).
     */
    private BuiltinData.Merchant merchant(String rawName) {
        List<String> names = new ArrayList<>();
        for (String name : List.of(NameNormalizer.merchant(rawName), NameNormalizer.stripped(rawName))) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        for (String name : names) {
            for (String u : data.undecided) {
                if (name.equals(u) || name.startsWith(u + " ") || (name.length() >= TRUNCATED_MIN && u.startsWith(name))) {
                    return null;
                }
            }
        }
        for (String name : names) {
            String compact = name.replace(" ", "");
            for (BuiltinData.Merchant m : data.merchants) {
                if (name.equals(m.name()) || name.startsWith(m.name() + " ")
                        || (m.compact().length() >= COMPACT_MIN && compact.equals(m.compact()))
                        || (m.compact().length() >= COMPACT_PREFIX_MIN && compact.startsWith(m.compact()))) {
                    return m;
                }
            }
        }
        for (String name : names) {
            if (name.length() < TRUNCATED_MIN) {
                continue;
            }
            String compact = name.replace(" ", "");
            BuiltinData.Merchant found = null;
            for (BuiltinData.Merchant m : data.merchants) {
                if (m.name().startsWith(name) || m.compact().startsWith(compact)) {
                    if (found != null && !found.category().equals(m.category())) {
                        return null;
                    }
                    if (found == null) {
                        found = m;
                    }
                }
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String mcc(String mcc) {
        if (mcc == null || mcc.isBlank()) {
            return null;
        }
        String code = mcc.trim();
        String exact = data.mccExact.get(code);
        if (exact != null) {
            return exact;
        }
        if (!FOUR_DIGITS.matcher(code).matches()) {
            return null;
        }
        int n = Integer.parseInt(code);
        for (BuiltinData.MccRange r : data.mccRanges) {
            if (n >= r.from() && n <= r.to()) {
                return r.category();
            }
        }
        return null;
    }

    /**
     * Two to four alphabetic words, none of them a business word; or a personal UPI handle. A single word counts
     * only where the mail says it is a person: a person-to-person tag, an honorific, or a personal handle that comes
     * with the name. A payee of a credit-card payment is never a person.
     */
    private boolean isPerson(Payee payee, boolean p2p) {
        if (payee.personalHandle) {
            return payee.personNames.stream().noneMatch(this::hasBusinessWord) && !localHasBusinessWord(payee.handleLocal);
        }
        
        for (String name : payee.personNames) {
            if (looksLikePerson(name)) {
                return true;
            }
            if (p2p && MOBILE_WITH_APP.matcher(name.trim()).matches()) {
                return true;
            }
            if ((p2p || payee.consumerHandle || hasHonorific(name)) && looksLikeSingleName(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHonorific(String name) {
        List<String> words = NameNormalizer.words(name);
        return words.size() > 1 && data.honorifics.contains(words.get(0));
    }

    /** One alphabetic word (after an honorific) that is not a business word. */
    private boolean looksLikeSingleName(String name) {
        List<String> words = new ArrayList<>(NameNormalizer.words(name));
        if (!words.isEmpty() && data.honorifics.contains(words.get(0))) {
            words.remove(0);
        }
        if (words.size() != 1 || !PLAIN_NAME.matcher(UPI_PREFIX.matcher(name).replaceFirst("")).matches()) {
            return false;
        }
        String w = words.get(0);
        return w.length() >= 4 && w.chars().allMatch(Character::isLetter) && !data.businessWords.contains(w)
                && data.businessWords.stream().noneMatch(b -> b.length() >= 4 && b.startsWith(w));
    }

    /** The name a mail greets its reader with ("Dear Asha Rao,"), when it is a name and not "Customer". */
    public static Optional<String> greetingName(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = GREETING.matcher(text.length() > TEXT_CHARS ? text.substring(0, TEXT_CHARS) : text);
        if (!m.find()) {
            return Optional.empty();
        }
        for (String w : NameNormalizer.words(m.group(1))) {
            if (GREETING_STOP.contains(w)) {
                return Optional.empty();
            }
        }
        return Optional.of(m.group(1));
    }

    /** The holder's name tokens: the caller's, else the greeting of the mail ("Dear Asha Rao,"). */
    private List<String> holder(CategorizeInput in, String text) {
        String name = in.holderName();
        if (name == null || name.isBlank()) {
            name = greetingName(text).orElse("");
        }
        List<String> out = new ArrayList<>();
        for (String w : NameNormalizer.words(name)) {
            if (GREETING_STOP.contains(w) || data.honorifics.contains(w) || !w.chars().allMatch(Character::isLetter)) {
                return List.of();
            }
            out.add(w);
        }
        return out;
    }

    /** Handles run words together ("jayakodicell"), so a long business word may sit anywhere inside. */
    private boolean localHasBusinessWord(String local) {
        for (String w : data.businessWords) {
            if (w.length() >= BUSINESS_SUBSTRING_MIN && local.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBusinessWord(String name) {
        for (String w : NameNormalizer.words(name)) {
            if (data.businessWords.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikePerson(String name) {
        if (!PLAIN_NAME.matcher(UPI_PREFIX.matcher(name).replaceFirst("")).matches()) {
            return false;
        }
        List<String> words = new ArrayList<>(NameNormalizer.words(name));
        if (!words.isEmpty() && data.honorifics.contains(words.get(0))) {
            words.remove(0);
        }
        if (words.size() < 2 || words.size() > 4) {
            return false;
        }
        int longest = 0;
        for (String w : words) {
            if (!w.chars().allMatch(Character::isLetter) || data.businessWords.contains(w)) {
                return false;
            }
            // Some banks cut the name short ("MOONLIGHT HOT"): a last word that starts a business word is one
            if (name.length() >= TRUNCATED_MIN && w == words.get(words.size() - 1) && w.length() >= 3
                    && data.businessWords.stream().anyMatch(b -> b.startsWith(w))) {
                return false;
            }
            longest = Math.max(longest, w.length());
        }
        return longest >= 3;
    }

    /**
     * The counterparty is the holder's own name (initials may differ) or their own UPI id. Needs a holder name
     * of two or more real words, and the counterparty has exactly those words: "K Rao" is not "Asha Rao".
     */
    private boolean isSelf(Payee payee, List<String> holder) {
        List<String> holderWords = holder.stream().filter(w -> w.length() > 1).toList();
        if (holderWords.size() < 2) {
            return false;
        }
        if (payee.handleLocal.length() >= 6 && payee.handleLocal.contains(String.join("", holderWords))) {
            return true;
        }
        for (String name : payee.personNames) {
            List<String> words = new ArrayList<>(NameNormalizer.words(name));
            if (!words.isEmpty() && data.honorifics.contains(words.get(0))) {
                words.remove(0);
            }
            if (words.stream().anyMatch(data.businessWords::contains)) {
                continue;
            }
            List<String> real = words.stream().filter(w -> w.length() > 1).toList();
            if ((real.containsAll(holderWords) && holderWords.containsAll(real)) || String.join("", real).equals(String.join("", holderWords))) {
                return true;
            }
        }
        return false;
    }

    /** What the input says about who was paid: names to look up, and whether a personal UPI handle is involved. */
    private static final class Payee {
        final List<String> names = new ArrayList<>();
        final List<String> personNames = new ArrayList<>();
        boolean personalHandle;
        boolean consumerHandle;
        String handleLocal = "";
        String display = "";
    }

    private static Payee payee(CategorizeInput in, String text) {
        Payee p = new Payee();
        String cp = in.counterparty() == null || in.counterparty().length() > MAX_COUNTERPARTY ? "" : in.counterparty().trim();
        String handle = null;
        String handleName = null;
        if (cp.isEmpty() || JUNK_COUNTERPARTY.matcher(cp).find()) {
            cp = "";
            for (Pattern pattern : TEXT_PAYEE) {
                Matcher m = pattern.matcher(text);
                if (m.find()) {
                    handle = pattern.pattern().contains("(?<handle>") ? m.group("handle") : null;
                    if (handle != null) {
                        handleName = m.group("name").trim();
                    } else {
                        cp = m.group("name").trim();
                    }
                    break;
                }
            }
        }
        Matcher spaced = SPACED_HANDLE.matcher(cp);
        if (handle == null && spaced.matches()) {
            handle = spaced.group(1) + "@" + spaced.group(2);
            cp = "";
        }
        if (handle == null) {
            int at = cp.indexOf('@');
            if (at > 0) {
                int start = cp.lastIndexOf(' ', at) + 1;
                int end = cp.indexOf(' ', at);
                handle = end < 0 ? cp.substring(start) : cp.substring(start, end);
                String rest = (cp.substring(0, Math.max(start - 1, 0)) + " " + (end < 0 ? "" : cp.substring(end))).trim();
                handleName = rest.isEmpty() ? null : rest;
            }
        }
        if (handle != null) {
            Matcher h = HANDLE.matcher(handle);
            boolean parsed = h.find();
            String local = parsed ? h.group(1) : handle;
            String bank = parsed ? h.group(2) : "";
            p.handleLocal = NOT_LETTER.matcher(local.toLowerCase(Locale.ROOT)).replaceAll("");
            boolean business = BUSINESS_BANK.matcher(bank).find();
            p.consumerHandle = !business && CONSUMER_BANK.matcher(bank).matches();
            p.personalHandle = !business && !CODE_LOCAL.matcher(local).find() && (MOBILE_HANDLE.matcher(local).matches()
                    || (p.consumerHandle && CONSUMER_LOCAL.matcher(local).matches()));
            String words = DIGITS.matcher(HANDLE_SEPARATORS.matcher(local).replaceAll(" ")).replaceAll(" ").replace("*", " ").trim();
            if (!words.isEmpty()) {
                p.names.add(words);
            }
            if (handleName != null) {
                addNames(p, handleName);
                p.personNames.add(handleName);
            }
            p.display = handle + (handleName == null ? "" : " " + handleName);
        } else {
            if (!cp.isEmpty()) {
                addNames(p, cp);
                p.personNames.add(cp);
            }
            p.display = cp;
        }
        return p;
    }

    /** The name as given and, for "GATEWAY*MERCHANT" or "MERCHANT *ITEM" forms, each side of the star. */
    private static void addNames(Payee p, String name) {
        p.names.add(name);
        int star = name.indexOf('*');
        if (star > 0) {
            p.names.add(name.substring(0, star));
            p.names.add(name.substring(star + 1));
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
