package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.MailDerived.AppHit;
import com.financeos.gmail.accountdetect.MailDerived.EventHit;
import com.financeos.gmail.accountdetect.MailDerived.LimitHit;
import com.financeos.gmail.accountdetect.MailDerived.Slot;
import com.financeos.gmail.accountdetect.MailDerived.VariantHit;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one in-memory mail into its derived features: layout cluster (sender domain + subject skeleton), a role
 * vote, every masked/labelled number with its slot key and label kind, product phrases, lifecycle statements and
 * payment-app issuer mentions. Deterministic; no AI.
 */
public final class FeatureExtractor {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Skeleton keeps this many leading tokens so merchant names at the end of a subject do not split a layout. */
    static final int SKELETON_TOKENS = 7;

    private FeatureExtractor() {}

    // ---------------------------------------------------------------- skeleton + role

    private static final Pattern MASKED_ANY = Pattern.compile("(?i)\\d{0,6}(?:[x*•#]{2,}[\\s-]?)+\\d{3,6}");
    private static final Pattern MONTHS = Pattern.compile(
            "\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b");

    /** Normalised subject skeleton: masked numbers → m, months → mon, digits → #, lower case, first N tokens. */
    public static String skeleton(String subject) {
        String s = fullSkeleton(subject);
        String[] toks = s.isEmpty() ? new String[0] : s.split(" ");
        return String.join(" ", Arrays.copyOf(toks, Math.min(toks.length, SKELETON_TOKENS)));
    }

    static String fullSkeleton(String subject) {
        String s = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        s = MASKED_ANY.matcher(s).replaceAll(" m ");
        s = MONTHS.matcher(s).replaceAll(" mon ");
        s = s.replaceAll("\\d+", "#");
        return s.replaceAll("[^a-z#]+", " ").trim();
    }

    static final Pattern OTP = Pattern.compile("\\b(?:otp|one[ -]time password|verification code)\\b");
    static final Pattern MARKETING = Pattern.compile(
            "pre-?approved|apply (?:now|for|today)|\\bexclusive\\b|limited (?:period|time)|\\beligible\\b|\\boffers?\\b|"
                    + "\\bupgrade (?:to|your|now)\\b|\\bget your\\b|\\bintroducing\\b|\\bdiscount|% off|\\bwin\\b|\\binvit|"
                    + "lifetime free|don'?t miss|\\bhurry\\b|\\bfestive\\b|\\bsale\\b|\\bvoucher|\\bdeals?\\b|\\bcongratulations\\b(?!.{0,40}(?:has been|is now|have been))");
    static final Pattern STATEMENT = Pattern.compile("statement|e-?stmt|\\bestatement");
    static final Pattern DUE = Pattern.compile("\\bdue\\b|reminder|overdue|minimum amount|payment pending|\\bpay now\\b|\\bbill\\b");
    static final Pattern CARD_SERVICE = Pattern.compile(
            "welcome|dispatch|deliver|activat|replac|reissu|renew|upgraded|add-?on|additional card|supplementary|\\bclosed\\b|"
                    + "closure|cancel|block|hotlist|\\blimit\\b|\\bkit\\b|new card|on (?:its|the) way|shipped|courier");
    static final Pattern TXN = Pattern.compile(
            "debited|credited|\\bspent\\b|transaction|\\btxn\\b|used (?:at|for)|withdrawn|withdrawal|purchase|payment received|"
                    + "received a payment|payment of|\\bpaid\\b|transferred|refund|reversal|\\bupi\\b|\\bneft\\b|\\bimps\\b|you have done");
    static final Pattern ACCOUNT_SERVICE = Pattern.compile(
            "(?:account|a/c).{0,40}(?:opened|closed|closure|kyc|nominee|cheque|update|welcome|activated|dormant|balance)|"
                    + "(?:kyc|nominee|cheque book|aadhaa?r)");

    /** Role vote from one header line (subject or snippet); OTHER when nothing decisive is said. */
    static Role roleOf(String text, boolean subjectLine, List<String> attachments) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (OTP.matcher(t).find()) return Role.OTP;
        if (MARKETING.matcher(t).find()) return Role.MARKETING;
        if (subjectLine && STATEMENT.matcher(t).find() && !t.contains("mini statement")) return Role.STATEMENT;
        if (subjectLine && DUE.matcher(t).find()) return Role.DUE_REMINDER;
        if (t.contains("card") && CARD_SERVICE.matcher(t).find()) return Role.CARD_SERVICE;
        if (TXN.matcher(t).find()) return Role.TXN_ALERT;
        if (ACCOUNT_SERVICE.matcher(t).find()) return Role.ACCOUNT_SERVICE;
        if (subjectLine) {
            for (String a : attachments) {
                String al = a.toLowerCase(Locale.ROOT);
                if (al.endsWith(".pdf") && (al.contains("stmt") || al.contains("statement"))) return Role.STATEMENT;
            }
        }
        return Role.OTHER;
    }

    static Role roleVote(MailFeature m) {
        Role r = roleOf(m.subject(), true, m.attachmentNames());
        if (r != Role.OTHER) return r;
        return roleOf(m.snippet(), false, List.of());
    }

    static Kind kindOfSkeleton(String sk) {
        if (sk.contains("credit card")) return Kind.CREDIT_CARD;
        if (sk.contains("debit card")) return Kind.DEBIT_CARD;
        if (sk.matches(".*\\b(?:account|a c|ac|savings|current)\\b.*")) return Kind.ACCOUNT;
        return null;
    }

    // ---------------------------------------------------------------- numbers

    /** A number token: optional leading digits, mask characters, last digits; or a bare digit run. */
    private static final Pattern NUMBER = Pattern.compile(
            "(?i)(?<![\\w@*•#])(?:(\\d{0,6}(?:[x*•#]{2,}[\\s-]?)+)(\\d{3,6})|(\\d{4,18}))(?![\\d])");
    private static final Set<String> FILLER = Set.of("the", "a", "an", "no", "nos", "number", "num", "ending",
            "in", "with", "is", "xx", "x", "has", "been", "was", "bank", "card's", "s", "by");
    private static final Set<String> KIND_WORDS = Set.of("card", "acct", "loan", "savings", "current");
    private static final Set<String> REF_WORDS = Set.of("ref", "reference", "utr", "rrn", "txn", "transaction", "order", "id", "receipt",
            "request", "srn", "trace", "invoice", "folio", "policy", "application", "voucher", "challan", "cheque", "chq");
    private static final Set<String> PREPAID_WORDS = Set.of("wallet", "prepaid", "gift", "forex", "fastag", "giftcard");
    private static final Set<String> ID_WORDS = Set.of("aadhaar", "aadhar", "uid", "pan", "mobile", "phone", "customer", "cust", "crn",
            "registered", "pin", "code", "password", "emp", "employee", "member", "user");

    record Num(String last4, LabelKind label, List<String> labelTokens, boolean masked, int start) {}

    /** Every number in a segment with its label. Rejected shapes come back with a REJECT_* label (for the dropped tally). */
    static List<Num> numbersIn(String seg) {
        List<Num> out = new ArrayList<>();
        if (seg == null || seg.isBlank()) return out;
        Matcher m = NUMBER.matcher(seg);
        boolean priorNumber = false;   // an earlier masked number in this segment (the source of a transfer)
        while (m.find()) {
            boolean masked = m.group(2) != null;
            String digits = masked ? m.group(2) : m.group(3);
            if (digits.length() < 4) continue;
            boolean hadPrior = priorNumber;
            if (masked) priorNumber = true;
            String last4 = digits.substring(digits.length() - 4);
            int end = m.end();
            String after = seg.substring(end, Math.min(seg.length(), end + 3));
            List<String> before = tokensBefore(seg, m.start(), 8);
            List<String> labelToks = labelTokens(before);
            if (after.startsWith("@") || (!before.isEmpty() && before.get(before.size() - 1).equals("vpa"))) {
                out.add(new Num(last4, LabelKind.REJECT_VPA, labelToks, masked, m.start()));
                continue;
            }
            if (!masked && (after.matches("^[.,]\\d.*") || precededByCurrency(seg, m.start()))) continue; // an amount
            if (!masked && partOfDate(seg, m.start(), end)) continue;   // a year: "31/10/2024", "31-Oct-2024", "Oct 2024"
            // the leading digits of a spaced card number ("4893 99XX XXXX 2496"): the number's last4 comes after the mask
            if (!masked && digits.length() <= 6 && LEADING_GROUP.matcher(seg.substring(end, Math.min(seg.length(), end + 12))).find()) continue;
            if (masked && transferDestination(seg, m.start(), end, hadPrior)) {
                out.add(new Num(last4, LabelKind.REJECT_DEST, labelToks, true, m.start()));
                continue;
            }
            String shape = m.group().replaceAll("[\\s-]", "");
            List<String> near = before.subList(Math.max(0, before.size() - 4), before.size());
            LabelKind kind = kindFrom(before);
            if (near.stream().anyMatch(t -> t.equals("otp"))) {
                out.add(new Num(last4, LabelKind.REJECT_OTP, labelToks, masked, m.start()));
                continue;
            }
            // a wallet or prepaid instrument (wallet, prepaid/gift/forex card, FASTag) is never an own account or card
            if (near.stream().anyMatch(PREPAID_WORDS::contains)) {
                out.add(new Num(last4, LabelKind.REJECT_PREPAID, labelToks, masked, m.start()));
                continue;
            }
            if (near.stream().anyMatch(t -> t.startsWith("aadha") || t.equals("uid"))
                    || (shape.length() == 12 && masked && kind != LabelKind.CREDIT_CARD && kind != LabelKind.DEBIT_CARD && kind != LabelKind.CARD
                    && m.group().matches("(?i)[x*•]{4}[\\s-][x*•]{4}[\\s-]\\d{4}"))) {
                out.add(new Num(last4, LabelKind.REJECT_AADHAAR, labelToks, masked, m.start()));
                continue;
            }
            if (near.stream().anyMatch(ID_WORDS::contains)) {
                out.add(new Num(last4, LabelKind.REJECT_ID, labelToks, masked, m.start()));
                continue;
            }
            if (near.stream().anyMatch(REF_WORDS::contains) && !nearestIsKindWord(near)) {
                out.add(new Num(last4, LabelKind.REJECT_REF, labelToks, masked, m.start()));
                continue;
            }
            if (!masked) {
                // bare digits count only right after an account/card label; a long unlabelled run is a reference
                // "card ... with a new number ending 7312": "ending" right before the digits, a card/account word close by
                // the raw text must be just "ending" (no date, digit or month between): "period ending 31/10/2024" is a date
                boolean endingLabel = !before.isEmpty() && before.get(before.size() - 1).equals("ending") && kind != LabelKind.NONE
                        && ENDING_RAW.matcher(seg.substring(Math.max(0, m.start() - 16), m.start())).find();
                boolean labelled = !before.isEmpty() && (immediateLabel(before) || endingLabel);
                if (!labelled) {
                    if (digits.length() >= 9) out.add(new Num(last4, LabelKind.REJECT_REF, labelToks, false, m.start()));
                    continue;
                }
                if (digits.length() >= 19) continue;
            }
            out.add(new Num(last4, kind, labelToks, masked, m.start()));
        }
        return out;
    }

    private static final String DEST_TAIL = "(?:a/c|account|acct)\\b(?:\\s*(?:no\\.?|number|ending(?: with| in)?|#))?[\\s:]*$";
    /** Always the other side of a transfer: beneficiary / payee / recipient, "transferred (sent, paid) to [the] A/c". */
    private static final Pattern DEST_ALWAYS = Pattern.compile("(?:\\b(?:beneficiary|payee|recipient)\\b\\s*(?:(?!(?:from|your|debited)\\b)[\\w.&'-]+\\s+){0,3}?"
            + "|\\b(?:transferred|transfer|sent|paid|remitted)\\s+to\\s+(?:the\\s+)?)" + DEST_TAIL);
    /** The other side only when another number came first in the sentence: "credited to [the] A/c", "to [the] A/c", "and A/c N is credited". */
    private static final Pattern DEST_AFTER_SOURCE = Pattern.compile("\\b(?:credited\\s+to\\s+(?:the\\s+)?|to\\s+(?:the\\s+)?)" + DEST_TAIL);
    private static final Pattern DEST_AND = Pattern.compile("\\band\\s+" + DEST_TAIL);
    private static final Pattern PARTY_AFTER = Pattern.compile("(?i)^\\s*(?:\\(\\s*)?(?:of|for)?\\s*(?:the\\s+)?(?:beneficiary|payee|recipient)\\b");
    private static final Pattern IS_CREDITED = Pattern.compile("^\\s*(?:is\\s+|has\\s+been\\s+)?credited\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A masked number that is the destination of a transfer from another number: never a slot. "your" before the number
     * means the user's own ("credited to your account **1234"), so it never matches these patterns.
     */
    static boolean transferDestination(String seg, int start, int end, boolean priorNumber) {
        // "... to A/c XX1234 of beneficiary NAME": the layout's counterparty slot already carries it as a contradiction
        if (PARTY_AFTER.matcher(seg.substring(end, Math.min(seg.length(), end + 40))).find()) return false;
        String before = seg.substring(Math.max(0, start - 100), start).toLowerCase(Locale.ROOT);
        if (DEST_ALWAYS.matcher(before).find()) return true;
        if (!priorNumber) return false;
        if (DEST_AFTER_SOURCE.matcher(before).find()) return true;
        return DEST_AND.matcher(before).find() && IS_CREDITED.matcher(seg.substring(end)).find();
    }

    private static final String MON = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern DATE_BEFORE = Pattern.compile("(?i)(?:\\d{1,2}\\s*[/\\-.]\\s*\\d{1,2}\\s*[/\\-.]\\s*"
            + "|\\b\\d{1,2}(?:st|nd|rd|th)?[\\s\\-/]*" + MON + "[\\s,\\-/']*"
            + "|\\b" + MON + "[\\s,\\-/']*(?:\\d{1,2}(?:st|nd|rd|th)?,?\\s*)?)$");
    private static final Pattern DATE_AFTER = Pattern.compile("^\\s*[/\\-.]\\s*(?:\\d{1,2}|" + MON + ")\\s*[/\\-.]\\s*\\d{1,2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_GROUP = Pattern.compile("(?i)^[\\s-]\\d{0,6}[x*•#]{2,}");
    private static final Pattern ENDING_RAW = Pattern.compile("(?i)\\bending\\s*[:\\-]?\\s*$");

    /** A 4-digit run that is the year of a date (dd/mm/yyyy, dd-Mon-yyyy, "Oct 2024", yyyy-mm-dd). */
    static boolean partOfDate(String seg, int start, int end) {
        if (end - start != 4) return false;
        String before = seg.substring(Math.max(0, start - 16), start);
        String after = seg.substring(end, Math.min(seg.length(), end + 10));
        return DATE_BEFORE.matcher(before).find() || DATE_AFTER.matcher(after).find();
    }

    private static boolean precededByCurrency(String seg, int start) {
        String b = seg.substring(Math.max(0, start - 5), start).toLowerCase(Locale.ROOT);
        return b.matches(".*(?:rs\\.?|inr|₹|usd|\\$)\\s*$");
    }

    private static boolean nearestIsKindWord(List<String> near) {
        for (int i = near.size() - 1; i >= 0; i--) {
            String t = near.get(i);
            if (KIND_WORDS.contains(t)) return true;
            if (REF_WORDS.contains(t)) return false;
        }
        return false;
    }

    /** The last one or two non-filler tokens before a bare number are an account/card word. */
    private static boolean immediateLabel(List<String> before) {
        int seen = 0;
        for (int i = before.size() - 1; i >= 0 && seen < 2; i--) {
            String t = before.get(i);
            if (KIND_WORDS.contains(t)) return true;
            if (t.equals("ending") || t.equals("no") || t.equals("number")) continue;
            seen++;
        }
        return false;
    }

    static List<String> tokensBefore(String seg, int pos, int max) {
        String head = seg.substring(Math.max(0, pos - 90), pos).toLowerCase(Locale.ROOT);
        head = head.replaceAll("\\ba/c\\b|\\bacct\\b|\\baccount\\b|\\bac\\b", " acct ");
        String[] raw = head.split("[^a-z']+");
        List<String> toks = new ArrayList<>();
        for (String t : raw) if (!t.isBlank()) toks.add(t);
        // the first token may be cut mid-word by the window
        if (pos - 90 > 0 && !toks.isEmpty()) toks.remove(0);
        return toks.subList(Math.max(0, toks.size() - max), toks.size());
    }

    private static List<String> labelTokens(List<String> before) {
        List<String> kept = new ArrayList<>();
        for (int i = before.size() - 1; i >= 0 && kept.size() < 3; i--) {
            String t = before.get(i);
            if (!FILLER.contains(t)) kept.add(0, t);
        }
        return kept;
    }

    /** Loan words: a number labelled with any of them is a loan, whatever account word sits beside it. */
    static final Set<String> LOAN_WORDS = Set.of("loan", "emi", "emis", "mortgage", "lan");

    /**
     * Nearest kind word within the last 6 tokens. A loan word among the label tokens wins ("Loan account no 1234",
     * "EMI a/c 1234"): the account word alone would make it an ACCOUNT.
     */
    static LabelKind kindFrom(List<String> before) {
        for (String t : labelTokens(before)) if (LOAN_WORDS.contains(t)) return LabelKind.LOAN;
        int lo = Math.max(0, before.size() - 6);
        for (int i = before.size() - 1; i >= lo; i--) {
            String t = before.get(i);
            if (t.equals("card")) {
                String p = i > 0 ? before.get(i - 1) : "";
                if (p.equals("credit")) return LabelKind.CREDIT_CARD;
                if (p.equals("debit")) return LabelKind.DEBIT_CARD;
                return LabelKind.CARD;
            }
            if (t.equals("acct") || t.equals("savings") || t.equals("current")) {
                if (i > 0 && LOAN_WORDS.contains(before.get(i - 1))) return LabelKind.LOAN;
                return LabelKind.ACCOUNT;
            }
            if (LOAN_WORDS.contains(t)) return LabelKind.LOAN;
        }
        return LabelKind.NONE;
    }

    // ---------------------------------------------------------------- segments

    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<!\\b(?:no|nos|a/c|acct|ac|rs|mr|ms|mrs|dr|st|ltd|pvt|inr|sr))[.!?](?=\\s+[A-Z])|[\\r\\n]+|\\s\\|\\s|\\u2022");

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String s : SENTENCE_SPLIT.split(clean(text))) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static String clean(String s) {
        return s.replace("&#39;", "'").replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace('\u00a0', ' ');
    }

    // ---------------------------------------------------------------- products + events

    static final Pattern OFFER_SEG = Pattern.compile(
            "(?i)\\bupgrade (?:to|your|now)\\b|\\bapply\\b|\\beligible\\b|pre-?approved|\\boffers?\\b|\\bexclusive\\b|\\binvit");

    /** Words that may sit between a product name and "card": issuer words plus network and finish words. */
    private static final Set<String> BETWEEN = Set.of("visa", "mastercard", "rupay", "metal", "edition", "contactless", "chip", "signature",
            "platinum", "infinite", "world");
    /** A welcome, dispatch or activation sentence: here "<Issuer> <Name>" names the product even without "card". */
    static final Pattern WELCOME_SEG = Pattern.compile("(?i)\\bwelcome\\b|dispatch|deliver|activat|\\bkit\\b|on (?:its|the) way|shipped|couriered");

    /**
     * Catalogue products in a product phrase, as canonical names: the name (or an alias) followed, after issuer,
     * network or finish words only, by "card" or "credit card" ("Regalia Gold Credit Card", "HDFC Bank Regalia Gold
     * Credit Card", "Swiggy HDFC Bank Credit Card" -> Swiggy). In a welcome/dispatch/activation sentence "<Issuer>
     * <Name>" also counts. A tier-only or common-word name counts only right next to the issuer's name. Never a
     * debit card or an account.
     */
    static List<String> productPhrases(String seg, String bank) {
        List<String> out = new ArrayList<>();
        if (seg == null || OFFER_SEG.matcher(seg).find()) return out;
        String norm = " " + ProductCatalog.norm(seg) + " ";
        Set<String> bw = BankDirectory.bankWords(bank);
        List<String> issuerNames = BankDirectory.issuerNames(bank);
        boolean welcome = WELCOME_SEG.matcher(seg).find();
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (var nd : ProductCatalog.needles(bank).entrySet()) {
            String needle = " " + nd.getKey() + " ";
            String canonical = nd.getValue();
            int idx = norm.indexOf(needle);
            while (idx >= 0) {
                int end = idx + needle.length() - 1;
                String head = norm.substring(0, idx + 1);
                boolean issuerBefore = false;
                for (String n : issuerNames) if (head.endsWith(" " + n + " ") || head.endsWith(" " + n + " bank ")) issuerBefore = true;
                String[] toks = norm.substring(end).trim().split(" ");
                int i = 0;
                boolean issuerAfter = false;
                while (i < toks.length && i < 5 && !toks[i].equals("card") && (bw.contains(toks[i]) || BETWEEN.contains(toks[i]))) {
                    if (bw.contains(toks[i]) && !Set.of("bank", "the", "your").contains(toks[i])) issuerAfter = true;
                    i++;
                }
                // a product is a credit-card name: "<Name> [issuer words] [credit] card", never "<Name> debit card"
                boolean phrase = i < toks.length && (toks[i].equals("card")
                        || toks[i].equals("credit") && i + 1 < toks.length && toks[i + 1].equals("card"));
                if (!phrase && issuerBefore && i < toks.length && toks[i].matches("x{2,}\\d*") && !ProductCatalog.tierOnly(canonical)) {
                    // "SBI Card SimplyCLICK XXXX XXXX XXXX 1234": issuer, name, then the masked card number
                    phrase = true;
                }
                if (!phrase && welcome && issuerBefore && !ProductCatalog.needsIssuer(nd.getKey())) {
                    // "Welcome to HDFC Bank Infinia": the name ends the phrase (no debit/account word after it)
                    String next = i < toks.length ? toks[i] : "";
                    phrase = !next.equals("debit") && !next.equals("acct") && !next.equals("account") && !next.equals("savings");
                }
                if (phrase && ProductCatalog.needsIssuer(nd.getKey())) phrase = issuerBefore || issuerAfter;
                if (phrase) {
                    // the phrase runs to its "card": a tier word inside it ("EazyDiner IndusInd Bank Platinum Credit
                    // Card") is part of this product, not a second one
                    int phraseEnd = end;
                    for (int k = 0; k < i && k < toks.length; k++) phraseEnd += toks[k].length() + 1;
                    boolean inside = false;
                    for (int[] sp : spans) if (idx >= sp[0] && idx + needle.length() - 1 <= sp[1]) inside = true;
                    if (!inside) {
                        spans.add(new int[]{idx, phraseEnd});
                        names.add(canonical);
                    }
                }
                idx = norm.indexOf(needle, idx + 1);
            }
        }
        for (String n : names) if (!out.contains(n)) out.add(n);
        return out;
    }

    /** A closure names the card or account as its subject; "cancelled" alone (a transaction, an order) never counts. */
    private static final Pattern CLOSED = Pattern.compile(
            "\\b(?:card|acct|account|a/c)\\b[^.]{0,60}?(?:has been (?:permanently )?closed|is (?:now )?(?:permanently )?closed|"
                    + "(?:has been |is )?closed successfully|has been successfully closed|"
                    + "closure (?:request )?(?:has been |is )?(?:processed|completed|successful|done))|"
                    + "\\bclosure of (?:your )?(?:\\w+ ){0,4}(?:card|acct|account|a/c)\\b[^.]{0,60}?(?:processed|completed|successful|done)");
    private static final Pattern CLOSED_NOT = Pattern.compile(
            "will be closed|closing balance|closing date|if not|request (?:has been )?(?:received|initiated|registered)|"
                    + "fixed deposit|\\bfd\\b|\\bloan\\b|\\bdeposit\\b|\\bticket\\b|complaint|service request|\\bsr\\b");
    /** A block is not a closure: with a new number after it, a replacement; otherwise a LOW status. */
    private static final Pattern BLOCKED = Pattern.compile(
            "\\b(?:card|acct|account|a/c)\\b[^.]{0,60}?(?:has been |is (?:now )?|was )(?:permanently )?(?:blocked|hotlisted)");
    /** Words that introduce the new number of a replacement: "new card ending Y", "replacement card Y", "reissued as Y". */
    private static final Pattern NEW_NUMBER_INTRO = Pattern.compile("\\bnew\\b|replacement|reissued|re-issued|replaced (?:by|with)");
    private static final Pattern REPLACED = Pattern.compile(
            "reissued|re-issued|replacement card|has been replaced|is replaced|replaces|renewed card|renewal card|new card (?:ending|number|no)");
    private static final Pattern UPGRADED = Pattern.compile("has been upgraded|is upgraded|upgraded to|upgrade (?:is |has been )?(?:complete|successful)");
    private static final Pattern ADDON = Pattern.compile(
            "add-?on (?:credit |debit )?cards?|add on (?:credit )?cards?|additional (?:credit )?cards?|supplementary (?:credit )?cards?|"
                    + "add-?on (?:member|cardholder|card ?holder)");

    static List<EventType> eventsIn(String seg) {
        List<EventType> out = new ArrayList<>();
        for (EventHit h : eventHitsIn(seg, List.of())) if (!out.contains(h.type())) out.add(h.type());
        return out;
    }

    /**
     * Lifecycle statements in one segment with the numbers they are about. A closure or block belongs only to the
     * number that is its subject (the own number nearest the phrase, inside "card ... has been closed"); a new number
     * introduced after it ("... closed and a new card Y issued") makes the pair a replacement instead.
     */
    static List<EventHit> eventHitsIn(String seg, List<Num> nums) {
        List<EventHit> out = new ArrayList<>();
        if (seg == null || OFFER_SEG.matcher(seg).find()) return out;
        String t = seg.toLowerCase(Locale.ROOT);   // same length as seg: positions line up
        List<Num> own = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Num n : nums) if (!n.label().rejected() && seen.add(n.last4())) own.add(n);
        List<String> all = List.copyOf(seen);
        boolean not = CLOSED_NOT.matcher(t).find();
        boolean replaced = false;
        for (EventType ty : List.of(EventType.CLOSED, EventType.BLOCKED)) {
            Matcher mm = (ty == EventType.CLOSED ? CLOSED : BLOCKED).matcher(t);
            if (not || !mm.find()) continue;
            String subject = null;
            for (Num n : own) if (n.start() >= mm.start() && n.start() < mm.end()) subject = n.last4();
            if (subject == null && !nums.isEmpty()) continue;   // a closure phrase about no number of ours
            String next = null;
            for (Num n : own) {
                if (n.start() < mm.end() || n.last4().equals(subject) || next != null) continue;
                if (NEW_NUMBER_INTRO.matcher(t.substring(mm.end(), n.start())).find()) next = n.last4();
            }
            if (subject != null && next != null) {
                out.add(new EventHit(EventType.REPLACED, List.of(subject, next)));
                replaced = true;
            } else {
                out.add(new EventHit(ty, subject == null ? List.of() : List.of(subject)));
            }
            break;
        }
        if (!replaced && REPLACED.matcher(t).find()) out.add(new EventHit(EventType.REPLACED, all));
        if (UPGRADED.matcher(t).find()) out.add(new EventHit(EventType.UPGRADED, all));
        if (ADDON.matcher(t).find()) out.add(new EventHit(EventType.ADD_ON, all));
        return out;
    }

    // ---------------------------------------------------------------- limit change

    /** "credit limit on your card XX1 has been increased to Rs 3,00,000", "your new credit limit is INR 2,50,000". */
    private static final Pattern LIMIT_CHANGE = Pattern.compile(
            "(?i)\\b(?:credit |card |total )?limit\\b.{0,80}?\\b(?:has been |is |was )?(?:increased|enhanced|revised|changed|updated|"
                    + "reduced|decreased|modified|upgraded)\\b.{0,40}?\\b(?:to|at)\\s*(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d+)?)"
                    + "|\\bnew (?:credit |card )?limit (?:is |of |will be )?(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d+)?)");
    /** Not the total credit limit: available, cash or temporary limits, and offers to raise one. */
    private static final Pattern LIMIT_NOT = Pattern.compile(
            "(?i)available|\\bcash\\b|temporary|\\btemp\\b|\\bupi\\b|daily|per transaction|withdrawal|transaction limit|\\bif\\b|request (?:has been )?(?:received|registered)");

    /** The new total credit limit in a limit-change sentence about exactly one own number, as a bucket. */
    static LimitHit limitHitIn(String seg, String single) {
        if (single == null || seg == null || OFFER_SEG.matcher(seg).find() || LIMIT_NOT.matcher(seg).find()) return null;
        Matcher m = LIMIT_CHANGE.matcher(seg);
        if (!m.find()) return null;
        String amount = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            String bucket = StatementContent.bucket(Double.parseDouble(amount.replace(",", "")));
            return bucket == null ? null : new LimitHit(single, bucket);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- main

    static final Pattern TRANSFER_WORDS = Pattern.compile(
            "\\b(?:imps|neft|rtgs|upi|standing instruction|si|beneficiary|payee|fund transfer|transferred to|transfer to|sent to)\\b");
    static final Pattern CREDIT_WORDS = Pattern.compile("credited to your|received in your|deposited (?:in|into|to) your");
    static final Pattern BALANCE_WORDS = Pattern.compile(
            "\\bavl\\.? ?bal|\\bavail(?:able|\\.)? ?bal|closing balance|opening balance|(?:a/c|acct|account|current|ledger) balance|"
                    + "balance (?:in|of) (?:your )?(?:a/c|acct|account)|account statement|statement of account|\\bbal(?:ance)?\\s*[:\\-]?\\s*(?:rs|inr|₹)");

    public static MailDerived derive(MailFeature m) {
        return derive(m, SALT);
    }

    /**
     * {@link #derive(MailFeature)} with a caller-held salt for cluster and slot keys: features derived in different
     * runs (e.g. a background job storing one user's features across restarts) share keys only when they share a salt.
     */
    public static MailDerived derive(MailFeature m, String salt) {
        if (salt == null) salt = SALT;
        String domain = m.senderDomain() == null ? "" : m.senderDomain().toLowerCase(Locale.ROOT);
        String bank = BankDirectory.bankForDomain(domain);
        if (bank == null) bank = "?" + domain;
        String sk = skeleton(m.subject());
        String clusterId = hash(salt, domain + "|" + sk);
        Role vote = roleVote(m);
        Kind clusterKind = kindOfSkeleton(fullSkeleton(m.subject()));

        Map<String, String> segments = new LinkedHashMap<>(); // segment text -> part tag (S, B, A)
        segments.put(clean(m.subject() == null ? "" : m.subject()), "S");
        String text = m.body() != null && !m.body().isBlank() ? m.body() : m.snippet();
        for (String s : sentences(text)) segments.putIfAbsent(s, "B");
        for (String a : m.attachmentNames()) segments.putIfAbsent(a.replaceAll("[_]", " "), "A");

        List<Slot> slots = new ArrayList<>();
        Set<String> slotSeen = new LinkedHashSet<>();
        List<VariantHit> variants = new ArrayList<>();
        List<EventHit> events = new ArrayList<>();
        List<AppHit> apps = new ArrayList<>();
        List<LimitHit> limits = new ArrayList<>();
        boolean app = BankDirectory.isApp(bank);
        for (var e : segments.entrySet()) {
            String seg = e.getKey();
            List<Num> nums = numbersIn(seg);
            Set<String> own = new LinkedHashSet<>();
            for (Num n : nums) {
                String key = hash(salt, clusterId + "|" + e.getValue() + "|" + String.join(" ", n.labelTokens()));
                if (slotSeen.add(key + n.last4())) slots.add(new Slot(key, n.last4(), n.label()));
                if (!n.label().rejected()) own.add(n.last4());
            }
            String single = own.size() == 1 ? own.iterator().next() : null;
            if (!app) {
                List<EventHit> evs = eventHitsIn(seg, nums);
                // "card XX1 has been upgraded to <Variant> Credit Card": the product cannot be tied to one number
                boolean changeSeg = evs.stream().anyMatch(h -> h.type() == EventType.REPLACED || h.type() == EventType.UPGRADED);
                for (String v : productPhrases(seg, bank)) variants.add(new VariantHit(v, changeSeg ? null : single));
                events.addAll(evs);
                LimitHit lh = Detector.placeholder(single) ? null : limitHitIn(seg, single);
                if (lh != null) limits.add(lh);
            } else {
                List<String> issuers = BankDirectory.issuersIn(seg.toLowerCase(Locale.ROOT));
                if (issuers.size() == 1 && single != null && !Detector.placeholder(single)) {
                    LabelKind lk = nums.stream().filter(n -> n.last4().equals(single)).map(Num::label).findFirst().orElse(LabelKind.NONE);
                    // the product the app names for that card ("HDFC Bank Regalia Gold Credit Card XX1"), one name only
                    List<String> pv = productPhrases(seg, issuers.get(0));
                    apps.add(new AppHit(issuers.get(0), single, lk, pv.size() == 1 ? pv.get(0) : null));
                }
            }
        }
        java.time.LocalDate date = m.date() == null ? null : m.date().atZone(IST).toLocalDate();
        String all = ((m.subject() == null ? "" : m.subject()) + " " + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
        // a debit/transfer layout; money received into the account ("credited to your A/c ... by NEFT") is not one
        boolean transfer = TRANSFER_WORDS.matcher(all).find() && !CREDIT_WORDS.matcher(all).find();
        int layout = (transfer ? MailDerived.TRANSFER : 0) | (BALANCE_WORDS.matcher(all).find() ? MailDerived.BALANCE : 0);
        return new MailDerived(bank, clusterId, vote, date, clusterKind, slots, variants, events, apps, layout, limits);
    }

    /**
     * Per-run salt for cluster and slot keys, so a cached key can not be matched to label words or subjects across
     * runs. Keys stay consistent within one run (and within one cache file, which stores the keys themselves).
     */
    private static final String SALT = Long.toHexString(new java.security.SecureRandom().nextLong());

    /** A fresh random salt for {@link #derive(MailFeature, String)}. */
    public static String newSalt() {
        return Long.toHexString(new java.security.SecureRandom().nextLong());
    }

    static String hash(String s) {
        return hash(SALT, s);
    }

    private static String hash(String salt, String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + "|" + s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
