package com.financeos.gmail.accountdetect.unlock;

import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.HolderCheck;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.time.YearMonth;
import com.financeos.statement.parser.PdfPasswords;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens a password-protected statement PDF with passwords built by rule. Candidates, in order: the user's saved statement
 * passwords for that bank (at most 3, rule label "SAVED"), the rules the mail itself named (its hint), then the researched
 * table's patterns for the bank and kind. Each is rendered from the profile, deduplicated, at most {@link #MAX_CANDIDATES} (each also in
 * lower and upper case, {@link #MAX_TRIES} in all) are tried, and the first that opens the PDF wins. The PDF is then read like an open one. A password lives only inside one
 * {@link #unlock} call; the result carries the rule that worked, never the password.
 */
public class StatementUnlocker {

    /** Candidates (before case variants) built per PDF. */
    public static final int MAX_CANDIDATES = 12;
    /** Passwords tried per PDF in all, case variants included (a wrong password is cheap to try). */
    public static final int MAX_TRIES = 36;
    public static final String SAVED = "SAVED";
    private static final int MAX_CARD_NUMBERS = 2;
    /** The user's own saved passwords for the bank are tried first, at most this many. */
    public static final int MAX_SAVED_FIRST = 3;

    /**
     * @param bank      bank id of the mail
     * @param kind      the mail's kind when known (null tries both kinds' table patterns)
     * @param hintRules rules parsed from the mail body (may be empty)
     * @param cardLast4 numbers the mail mentions, for CARD4 (at most two are used)
     * @param fallback  period to use when the statement prints none
     */
    public record Target(String bank, Kind kind, List<String> hintRules, List<String> cardLast4, YearMonth fallback) {
        public Target {
            hintRules = hintRules == null ? List.of() : List.copyOf(hintRules);
            cardLast4 = cardLast4 == null ? List.of() : List.copyOf(cardLast4);
        }
    }

    public enum State { UNLOCKED, FAILED, LOCKED }

    /**
     * @param state  UNLOCKED: a candidate opened it; FAILED: at least one was tried, none opened it; LOCKED: nothing to try
     * @param rule   the rule that worked (tagged tokens, or "SAVED"), only when UNLOCKED
     * @param tries  passwords tried
     * @param read   the statement read from the opened PDF (null unless UNLOCKED; its content may still be null)
     */
    public record Outcome(State state, String rule, int tries, StatementPdfReader.Result read) {}

    private final StatementPasswordRules table;

    public StatementUnlocker(StatementPasswordRules table) {
        this.table = table;
    }

    public static StatementUnlocker bundled() {
        return new StatementUnlocker(StatementPasswordRules.bundled());
    }

    public Outcome unlock(byte[] pdf, Target target, UnlockProfile profile) {
        // password -> rule label; insertion order is the order tried
        Map<String, String> candidates = new LinkedHashMap<>();
        List<String> cards = target.cardLast4().stream().distinct().limit(MAX_CARD_NUMBERS).toList();
        List<String> rules = new ArrayList<>(target.hintRules());
        String kind = target.kind() == null ? null : target.kind() == Kind.CREDIT_CARD ? "credit_card" : target.kind() == Kind.ACCOUNT ? "account" : null;
        // a debit-card mail belongs to the bank account's rules
        if (target.kind() == Kind.DEBIT_CARD) kind = "account";
        if (target.bank() != null) {
            int saved = 0;
            for (String pwd : profile.savedByBank().getOrDefault(target.bank(), List.of())) {
                if (saved >= MAX_SAVED_FIRST) break;
                if (pwd != null && !pwd.isBlank() && candidates.putIfAbsent(pwd, SAVED) == null) saved++;
            }
            rules.addAll(table.patterns(target.bank(), kind));
        }
        for (String rule : rules) {
            if (candidates.size() >= MAX_CANDIDATES) break;
            for (PasswordRender.Candidate c : PasswordRender.render(rule, profile.displayName(), profile.birthDate(), cards)) {
                if (candidates.size() >= MAX_CANDIDATES) break;
                candidates.putIfAbsent(c.password(), c.rule());
            }
        }
        if (candidates.isEmpty()) return new Outcome(State.LOCKED, null, 0, null);

        // each candidate is followed by its lowercase / uppercase forms (some PDFs need the other case); the rule label
        // follows the password that opens, so a stored rule re-renders to it
        Map<String, String> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : candidates.entrySet()) {
            expanded.putIfAbsent(c.getKey(), c.getValue());
            for (String v : PdfPasswords.variants(c.getKey())) {
                if (!v.equals(c.getKey())) expanded.putIfAbsent(v, retag(c.getValue(), v, c.getKey()));
            }
        }
        int tries = 0;
        for (Map.Entry<String, String> c : expanded.entrySet()) {
            if (tries >= MAX_TRIES) break;
            tries++;
            if (!opens(pdf, c.getKey())) continue;
            StatementPdfReader.Result read = StatementPdfReader.read(pdf, "PDF_MAIL", target.bank(), target.fallback(), c.getKey(), HolderCheck.forName(profile.displayName()));
            return new Outcome(State.UNLOCKED, c.getValue(), tries, read);
        }
        return new Outcome(State.FAILED, null, tries, null);
    }

    /** The rule for a case variant of a rendered password: lowercase tags every NAME token L, uppercase tags it U. */
    private static String retag(String rule, String variant, String original) {
        if (SAVED.equals(rule)) return rule;
        if (variant.equals(original.toLowerCase(java.util.Locale.ROOT))) return rule.replaceAll("(NAME[2-8])U", "$1L");
        return rule.replaceAll("(NAME[2-8])L", "$1U");
    }

    /** The numbers a mail's own text names (card-labelled first), at most two: the CARD4 candidates. */
    public static List<String> cardNumbers(MailDerived d) {
        List<String> out = new ArrayList<>();
        if (d == null) return out;
        for (boolean cardFirst : new boolean[]{true, false}) {
            for (MailDerived.Slot s : d.slots()) {
                boolean card = s.label() == LabelKind.CREDIT_CARD || s.label() == LabelKind.DEBIT_CARD || s.label() == LabelKind.CARD;
                if (card == cardFirst && s.last4() != null && !out.contains(s.last4())) out.add(s.last4());
            }
        }
        return out.size() > MAX_CARD_NUMBERS ? new ArrayList<>(out.subList(0, MAX_CARD_NUMBERS)) : out;
    }

    private static boolean opens(byte[] pdf, String password) {
        try (PDDocument doc = Loader.loadPDF(pdf, password)) {
            return true;
        } catch (InvalidPasswordException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
