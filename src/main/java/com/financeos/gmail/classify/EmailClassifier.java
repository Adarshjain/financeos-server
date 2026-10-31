package com.financeos.gmail.classify;

import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, pure email classifier (ALERT / STATEMENT / OTHER plus a subtype). Decision order, first
 * match wins: statement attachment, OTP, transaction sentence, no-transaction cues, UNKNOWN.
 * All patterns are precompiled, have bounded windows and no nested quantifiers. Never throws.
 */
@Component
public class EmailClassifier {

    public static final int VERSION = 1;

    // ---------------------------------------------------------------------------------------------
    // Word lists: extend here. All patterns match against lowercase text, currency already as "INR".
    // ---------------------------------------------------------------------------------------------

    private static final Set<String> STATEMENT_ATTACHMENT_EXTENSIONS = Set.of(".pdf", ".xlsx", ".xls");
    private static final Set<String> STATEMENT_ATTACHMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Pattern STATEMENT_WORDS = word("e-?statement|statement|stmt|account summary|passbook|transaction history");
    private static final Pattern DOCUMENT_WORDS = word("certificate|invoice|receipt|policy|form 16|tds|welcome kit|terms and conditions|terms & conditions|key fact|schedule of charges|tax");
    private static final Pattern CARD_CONTEXT = word("credit card|debit card|card|cc");
    private static final Pattern ACCOUNT_CONTEXT = Pattern.compile("\\b(?:savings|current account|account|a/c)\\b");

    private static final Pattern OTP_SUBJECT = Pattern.compile(
            "\\botp\\b.{0,30}?\\b(?:is|for)\\b|\\b(?:your|the|an?) otp\\b|^\\s*otp\\s*$|\\bone[- ]time password\\b|\\bverification code\\b");
    private static final Pattern OTP_BODY = Pattern.compile(
            "\\b\\d{4,8}\\b\\s+is\\s+(?:your|the)\\s+(?:otp|one[- ]time password)"
                    + "|\\botp\\s*(?:is|:)\\s*\\d{4,8}\\b"
                    + "|\\botp\\b.{0,80}?\\bis\\s+\\d{4,8}\\b"
                    + "|\\b(?:verification code|one[- ]time password)\\s*(?:is|:)?\\s*\\d{4,8}\\b");

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:\\b(?:inr|usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|hkd)|[$€£])\\s?\\d[\\d,]{0,15}(?:\\.\\d{1,2})?");
    /** Verbs that alone make a sentence a transaction sentence. "has been made" / "thank you for using" are phrases, not loose words. */
    private static final Pattern TXN_VERB = word("debited|credited|spent|withdrawn|paid|received|deposited|transferred|sent|charged"
            + "|reversed|refunded|reversal|refund|(?:has|have|was|were)(?: been)? made|thank you for using");
    /** Loose verbs: count only with a card/account reference in the same sentence and no security/bill/balance cue. */
    private static final Pattern LOOSE_VERB = word("used|successful|successfully");
    private static final Pattern CREDIT_VERB = word("credited|received|deposited");
    private static final Pattern DEBIT_VERB = word("debited|spent|withdrawn|paid|transferred|sent|used|charged");
    private static final Pattern REVERSAL_WORD = word("reversed|reversal|refunded|refund");
    private static final int TXN_WINDOW = 120;
    private static final int SHORT_LEAD_IN_CHARS = 30;

    /** A sentence with this wording is about a future, conditional or already-settled amount, not an event. */
    private static final Pattern FUTURE_GUARD = Pattern.compile(
            "\\bwill\\b|\\bwould\\b|\\bto be (?:debited|charged|deducted)\\b|\\b(?:is|are|was) due\\b|\\bdue (?:on|by|date)\\b"
                    + "|\\b(?:payment|amount|minimum|total) due\\b|\\bscheduled\\b|\\bupcoming\\b"
                    + "|\\b(?:pay|payable)\\b.{0,60}?\\b(?:by|before)\\b|\\balready (?:paid|made)\\b");
    /** A sentence selling something (cashback, offer...) is never the transaction sentence. */
    private static final Pattern PROMO_SENTENCE_GUARD = Pattern.compile(
            "\\b(?:offers?|discount|coupon|voucher|win|save)\\b|(?<!\\d)\\d{1,3}%\\s*off");
    /** "Cashback" sells unless the sentence says it was credited/received ("INR 50 cashback credited to your card"). */
    private static final Pattern CASHBACK = Pattern.compile("\\bcashback\\b");

    private static final Pattern DECLINE_WORDS = Pattern.compile(
            "\\bdeclined\\b|\\bfailed\\b|\\bunsuccessful\\b|could not be (?:processed|completed)|insufficient (?:balance|funds)|\\bnot (?:successful|completed)\\b");

    /** Account / card reference: ending 1234, XX1234, **1234, A/c, card no, VPA, UPI. */
    private static final Pattern ACCOUNT_REF = Pattern.compile(
            "\\bending(?: with| in)?\\s*\\d{3,}|\\bx{1,12}\\s?\\d{3,}|\\*{1,12}\\s?\\d{3,}|•{1,12}\\s?\\d{3,}|\\ba/c\\b|\\baccount (?:no|number|#)\\b"
                    + "|\\bcard (?:no|number|#)\\b|\\bcard\\s+\\d{4}\\b|\\bvpa\\b|\\bupi\\b");

    /** Labelled-field alerts (no verb): "Amount: INR 50 Merchant: X Card: XX1234". "Amount due:" is not an amount label. */
    private static final Pattern LABELLED_AMOUNT = Pattern.compile(
            "(?<!total )(?<!minimum )(?<!outstanding )\\b(?:transaction amount|txn amount|txn amt|transaction value|amount|amt)\\s*[:\\-]\\s*"
                    + "(?:\\(inr\\)\\s*)?(?:\\b(?:inr|usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|hkd)|[$\u20AC\u00A3])\\s?\\d");
    private static final Pattern TRANSACTION_LABEL = Pattern.compile(
            "(?<!due )(?<!statement )\\b(?:merchant name|merchant|at|to|from|transaction date|txn date|date(?: (?:&|and) time)?|card|account|ref no|upi ref|transaction type)\\s*:");
    private static final Pattern CREDIT_LABEL = Pattern.compile(
            "\\b(?:transaction type|type)\\s*[:\\-]\\s*(?:credit|cr)\\b|\\bcredited\\b|\\breceived\\b|\\bcr\\b");

    private static final Pattern UPCOMING_DEBIT = Pattern.compile(
            "\\b(?:will|to) be (?:auto-?)?(?:debited|charged|deducted)\\b|\\bauto-?debit\\b|\\bstanding instruction\\b|\\be-?mandate\\b"
                    + "|\\bmandate\\b|\\bauto-?pay\\b|\\bupcoming (?:debit|payment|emi|charge)s?\\b|\\bscheduled (?:debit|payment)\\b");
    private static final Pattern BILL_DUE = Pattern.compile(
            "\\bpayment due\\b|\\btotal (?:amount )?due\\b|\\bminimum (?:amount )?due\\b|\\bamount due\\b"
                    + "|\\bbill\\b.{0,80}?\\b(?:pay|payable)\\b|\\b(?:pay|payable)\\b.{0,30}?\\binr\\s?\\d.{0,60}?\\b(?:by|before)\\b");
    private static final Pattern STATEMENT_NOTICE = Pattern.compile("\\bstatement\\b[^.]{0,80}?\\b(?:ready|generated|available)\\b");
    private static final Pattern BALANCE = Pattern.compile("\\bavailable balance\\b|\\bbalance is\\b|\\bbalance in\\b|\\bbalance as of\\b|\\baccount balance\\b");
    private static final Pattern SECURITY = Pattern.compile(
            "\\blogin\\b|\\blog(?:ged)? in\\b|\\bpassword\\b|\\bnew device\\b|\\bregistered device\\b|\\bcard (?:has been )?(?:blocked|locked|hotlisted)\\b"
                    + "|\\blimit (?:has been )?(?:changed|updated|revised)\\b|\\b(?:atm |card |upi |m)?pin (?:has been )?(?:set|reset|changed|generated)\\b"
                    + "|\\b(?:set|reset|change|generate) (?:your )?(?:atm |card |upi |m)?pin\\b|\\bsuspicious\\b");
    private static final Pattern SERVICE = Pattern.compile(
            "\\bkyc\\b|\\b(?:profile|address|e-?mail|mobile(?: number)?|contact details)\\b[^.]{0,40}?\\b(?:updated|changed|registered|verified)\\b"
                    + "|\\brequest (?:has been |is )?(?:received|registered|logged)\\b|\\bwelcome (?:to|aboard)\\b");
    private static final Pattern PROMOTION = Pattern.compile(
            "\\bunsubscribe\\b|\\boffers?\\b|\\bapply now\\b|\\bpre-?approved\\b|\\blimited period\\b|\\bt&c\\b|\\bterms (?:and|&) conditions\\b|(?<!\\d)\\d{1,3}%\\s*off|\\bexclusive\\b");

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<!(?i:\\b(?:no|ref|txn|amt)\\.))(?<=[.?])\\s+(?=[A-Z])|\\s*" + EmailText.SECTION + "\\s*");
    private static final Pattern FILENAME_SEPARATORS = Pattern.compile("[_.]+");

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }

    public ClassificationResult classify(GmailMessage message) {
        try {
            return doClassify(message);
        } catch (RuntimeException | StackOverflowError e) {
            return new ClassificationResult(EmailKind.OTHER, EmailSubtype.UNKNOWN, ClassifierConfidence.LOW, null,
                    List.of("error"), VERSION);
        }
    }

    private ClassificationResult doClassify(GmailMessage message) {
        List<String> signals = new ArrayList<>();
        if (message == null) {
            return result(EmailSubtype.UNKNOWN, ClassifierConfidence.LOW, null, signals);
        }
        String bankId = BankSenderDomains.bankIdFor(extractAddress(message.from()));
        if (bankId != null) {
            signals.add("bank:" + bankId);
        }
        EmailText email = EmailText.of(message);
        String lower = email.lower();
        String subjectLower = message.subject() == null ? "" : message.subject().toLowerCase(Locale.ROOT);

        // 1. Statement attachment
        String attachmentKind = statementAttachmentKind(message.attachments());
        if (attachmentKind != null) {
            signals.add("att:" + attachmentKind);
            return classifyAttachment(message, lower, subjectLower, bankId, signals);
        }

        List<String> sentences = sentences(email.text());

        // 2. OTP: a real OTP subject, or the otp token and its digits in one sentence that is no transaction sentence
        if (OTP_SUBJECT.matcher(subjectLower).find() || hasOtpSentence(sentences)) {
            signals.add("otp");
            return result(EmailSubtype.OTP, ClassifierConfidence.HIGH, bankId, signals);
        }

        // 3. Transaction sentence
        boolean hasAmount = AMOUNT.matcher(lower).find();
        if (hasAmount) {
            signals.add("amt");
        }
        TransactionSentence txn = hasAmount ? findTransactionSentence(sentences) : null;
        boolean declined = DECLINE_WORDS.matcher(lower).find();
        if (txn != null) {
            if (declinedNear(sentences, txn.index())) {
                signals.add("decline");
                return result(EmailSubtype.DECLINED, ClassifierConfidence.HIGH, bankId, signals);
            }
            for (String verb : txn.verbs()) {
                signals.add("verb:" + verb);
            }
            boolean hasRef = ACCOUNT_REF.matcher(lower).find();
            if (hasRef) {
                signals.add("ref:card");
            }
            if (PROMOTION.matcher(lower).find()) {
                signals.add("promo:ignored");
            }
            return result(alertSubtype(txn), hasRef ? ClassifierConfidence.HIGH : ClassifierConfidence.MEDIUM, bankId, signals);
        }

        // 3b. Labelled fields only ("Amount: INR 50 Merchant: X"), no verb; decline wording falls through to step 4.
        if (hasAmount && !declined && ACCOUNT_REF.matcher(lower).find() && hasLabelledTransaction(sentences)) {
            signals.add("labels");
            signals.add("ref:card");
            EmailSubtype subtype = REVERSAL_WORD.matcher(lower).find() ? EmailSubtype.REVERSAL
                    : CREDIT_LABEL.matcher(lower).find() ? EmailSubtype.CREDIT : EmailSubtype.DEBIT;
            return result(subtype, ClassifierConfidence.MEDIUM, bankId, signals);
        }

        // 4. No transaction sentence
        if (hasAmount && declined) {
            signals.add("decline");
            return result(EmailSubtype.DECLINED, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (UPCOMING_DEBIT.matcher(lower).find()) {
            signals.add("future");
            return result(EmailSubtype.UPCOMING_DEBIT, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (BILL_DUE.matcher(lower).find()) {
            signals.add("kw:due");
            return result(EmailSubtype.BILL_DUE, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (STATEMENT_NOTICE.matcher(lower).find()) {
            signals.add("kw:statement");
            return result(EmailSubtype.STATEMENT_NOTICE, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (BALANCE.matcher(lower).find()) {
            signals.add("kw:balance");
            return result(EmailSubtype.BALANCE, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (SECURITY.matcher(lower).find()) {
            signals.add("kw:security");
            return result(EmailSubtype.SECURITY, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (SERVICE.matcher(lower).find()) {
            signals.add("kw:service");
            return result(EmailSubtype.SERVICE, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (PROMOTION.matcher(lower).find()) {
            signals.add("promo");
            return result(EmailSubtype.PROMOTION, ClassifierConfidence.MEDIUM, bankId, signals);
        }

        // 5. Nothing matched
        return result(EmailSubtype.UNKNOWN, ClassifierConfidence.LOW, bankId, signals);
    }

    private ClassificationResult classifyAttachment(GmailMessage message, String lower, String subjectLower,
                                                    String bankId, List<String> signals) {
        StringBuilder names = new StringBuilder();
        for (GmailAttachment att : message.attachments()) {
            if (att != null && att.filename() != null) {
                names.append(' ').append(FILENAME_SEPARATORS.matcher(att.filename().toLowerCase(Locale.ROOT)).replaceAll(" "));
            }
        }
        String head = subjectLower + names;
        boolean statementInHead = STATEMENT_WORDS.matcher(head).find();
        boolean statementInBody = !statementInHead && STATEMENT_WORDS.matcher(lower).find();
        if (statementInHead || (statementInBody && !DOCUMENT_WORDS.matcher(head).find())) {
            signals.add("kw:statement");
            EmailSubtype subtype = EmailSubtype.STATEMENT;
            if (CARD_CONTEXT.matcher(head).find()) {
                subtype = EmailSubtype.CARD_STATEMENT;
            } else if (ACCOUNT_CONTEXT.matcher(head).find()) {
                subtype = EmailSubtype.ACCOUNT_STATEMENT;
            } else if (CARD_CONTEXT.matcher(lower).find()) {
                subtype = EmailSubtype.CARD_STATEMENT;
            } else if (ACCOUNT_CONTEXT.matcher(lower).find()) {
                subtype = EmailSubtype.ACCOUNT_STATEMENT;
            }
            // Statement words in the subject or filename are strong; in the body alone they are only a hint.
            return result(subtype, statementInHead ? ClassifierConfidence.HIGH : ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (DOCUMENT_WORDS.matcher(head + " " + lower).find()) {
            signals.add("kw:doc");
            return result(EmailSubtype.DOCUMENT, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        return result(EmailSubtype.STATEMENT, ClassifierConfidence.LOW, bankId, signals);
    }

    /** "pdf" or "xls" when the message carries a statement-like attachment, else null. */
    private static String statementAttachmentKind(List<GmailAttachment> attachments) {
        if (attachments == null) {
            return null;
        }
        for (GmailAttachment att : attachments) {
            if (att == null) {
                continue;
            }
            String name = att.filename() == null ? "" : att.filename().toLowerCase(Locale.ROOT);
            String mime = att.mimeType() == null ? "" : att.mimeType().toLowerCase(Locale.ROOT).trim();
            boolean byName = STATEMENT_ATTACHMENT_EXTENSIONS.stream().anyMatch(name::endsWith);
            if (byName || STATEMENT_ATTACHMENT_MIME_TYPES.contains(mime)) {
                boolean pdf = name.endsWith(".pdf") || (!byName && mime.equals("application/pdf"));
                return pdf ? "pdf" : "xls";
            }
        }
        return null;
    }

    private record TransactionSentence(List<String> verbs, String lowerSentence, int index) {}

    private static boolean isGuarded(String lower) {
        if (FUTURE_GUARD.matcher(lower).find() || PROMO_SENTENCE_GUARD.matcher(lower).find()) {
            return true;
        }
        return CASHBACK.matcher(lower).find() && !CREDIT_VERB.matcher(lower).find();
    }

    /**
     * Splits into sentences. A short lead-in ("Transaction successful.", a table label) joins the sentence after
     * it, but a guarded sentence (promo, future wording) never takes or gives a prefix, so a banner or footer
     * cannot swallow or poison the alert next to it. A trailing short fragment is its own sentence.
     */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder prefix = new StringBuilder();
        for (String raw : SENTENCE_SPLIT.split(text)) {
            if (raw.isBlank()) {
                continue;
            }
            if (isGuarded(raw.toLowerCase(Locale.ROOT))) {
                flush(prefix, out);
                out.add(raw);
            } else if (raw.length() <= SHORT_LEAD_IN_CHARS) {
                prefix.append(raw).append(' ');
            } else {
                out.add(prefix + raw);
                prefix.setLength(0);
            }
        }
        flush(prefix, out);
        return out;
    }

    private static void flush(StringBuilder prefix, List<String> out) {
        if (prefix.length() > 0) {
            out.add(prefix.toString().trim());
            prefix.setLength(0);
        }
    }

    private static boolean hasOtpSentence(List<String> sentences) {
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (OTP_BODY.matcher(lower).find() && !TXN_VERB.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    /** Decline wording counts only in the transaction sentence and its immediate neighbours, not in a footer. */
    private static boolean declinedNear(List<String> sentences, int index) {
        for (int i = Math.max(0, index - 1); i <= Math.min(sentences.size() - 1, index + 1); i++) {
            if (DECLINE_WORDS.matcher(sentences.get(i).toLowerCase(Locale.ROOT)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The first sentence holding a currency amount and a past-tense transaction verb within the window. */
    private static TransactionSentence findTransactionSentence(List<String> sentences) {
        for (int index = 0; index < sentences.size(); index++) {
            String lower = sentences.get(index).toLowerCase(Locale.ROOT);
            if (isGuarded(lower)) {
                continue;
            }
            boolean looseAllowed = ACCOUNT_REF.matcher(lower).find()
                    && !SECURITY.matcher(lower).find() && !BILL_DUE.matcher(lower).find() && !BALANCE.matcher(lower).find();
            Matcher amount = AMOUNT.matcher(lower);
            while (amount.find()) {
                int from = Math.max(0, amount.start() - TXN_WINDOW);
                int to = Math.min(lower.length(), amount.end() + TXN_WINDOW);
                List<String> verbs = new ArrayList<>();
                collectVerbs(TXN_VERB, lower, from, to, verbs);
                if (looseAllowed) {
                    collectVerbs(LOOSE_VERB, lower, from, to, verbs);
                }
                if (!verbs.isEmpty()) {
                    return new TransactionSentence(verbs, lower, index);
                }
            }
        }
        return null;
    }

    private static void collectVerbs(Pattern pattern, String lower, int from, int to, List<String> verbs) {
        Matcher verb = pattern.matcher(lower).region(from, to).useTransparentBounds(true);
        while (verb.find()) {
            if (!verbs.contains(verb.group())) {
                verbs.add(verb.group());
            }
        }
    }

    private static boolean hasLabelledTransaction(List<String> sentences) {
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (isGuarded(lower)) {
                continue;
            }
            if (LABELLED_AMOUNT.matcher(lower).find() && TRANSACTION_LABEL.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private static EmailSubtype alertSubtype(TransactionSentence txn) {
        String s = txn.lowerSentence();
        if (REVERSAL_WORD.matcher(s).find()) {
            return EmailSubtype.REVERSAL;
        }
        // The first transaction verb in the sentence decides when it holds both a debit and a credit verb.
        Matcher credit = CREDIT_VERB.matcher(s);
        Matcher debit = DEBIT_VERB.matcher(s);
        boolean hasCredit = credit.find();
        boolean hasDebit = debit.find();
        if (hasCredit && (!hasDebit || credit.start() < debit.start())) {
            return EmailSubtype.CREDIT;
        }
        return EmailSubtype.DEBIT;
    }

    private static String extractAddress(String from) {
        if (from == null) {
            return null;
        }
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    private static ClassificationResult result(EmailSubtype subtype, ClassifierConfidence confidence,
                                               String bankId, List<String> signals) {
        return new ClassificationResult(subtype.kind(), subtype, confidence, bankId, List.copyOf(signals), VERSION);
    }
}
