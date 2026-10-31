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

    public static final int VERSION = 2;

    // ---------------------------------------------------------------------------------------------
    // Word lists: extend here. All patterns match against lowercase text, currency already as "INR".
    // ---------------------------------------------------------------------------------------------

    private static final Set<String> STATEMENT_ATTACHMENT_EXTENSIONS = Set.of(".pdf", ".xlsx", ".xls");
    private static final Set<String> STATEMENT_ATTACHMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Pattern STATEMENT_WORDS = word("e-?statement|statements?|stmt|account summary|passbook|transaction history");
    private static final Pattern DOCUMENT_WORDS = word("certificate|invoice|receipt|policy|form 16|tds|welcome kit|terms and conditions|terms & conditions|key fact|schedule of charges|tax"
            + "|letter|cancellation|application ?form|application|advisory|emailer");
    private static final Pattern CARD_CONTEXT = word("credit card|debit card|card|cc");
    private static final Pattern ACCOUNT_CONTEXT = Pattern.compile("\\b(?:savings|current accounts?|accounts?|a/c)\\b");

    private static final Pattern OTP_SUBJECT = Pattern.compile(
            "\\botp\\b.{0,30}?\\b(?:is|for)\\b|\\b(?:your|the|an?) otp\\b|^\\s*otp\\s*$|\\bone[- ]time password\\b|\\bverification code\\b");
    /** A subject that ends in "OTP" counts only when the body has a 4-8 digit code right after the otp token. */
    private static final Pattern OTP_SUBJECT_ENDING = Pattern.compile("\\botp\\s*$");
    private static final Pattern OTP_CODE_NEARBY = Pattern.compile("\\botp\\b.{0,30}?\\b\\d{4,8}\\b");
    private static final Pattern OTP_BODY = Pattern.compile(
            "\\b\\d{4,8}\\b\\s+is\\s+(?:your|the)\\s+(?:otp|one[- ]time password)"
                    + "|\\botp\\s*(?:is\\s*:?|:)\\s*\\d{4,8}\\b"
                    + "|\\botp\\b.{0,80}?\\bis\\s+\\d{4,8}\\b"
                    + "|\\b(?:verification code|one[- ]time password)\\s*(?:is|:)?\\s*\\d{4,8}\\b");

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:\\b(?:inr|usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|hkd)|[$€£])\\s?\\d[\\d,]{0,15}(?:\\.\\d{1,2})?");
    /** Verbs that alone make a sentence a transaction sentence. "has been made" / "thank you for using" are phrases, not loose words. */
    private static final Pattern TXN_VERB = word("debited|credited|spent|withdrawn|paid|received|deposited|transferred|sent|charged"
            + "|reversed|refunded|reversal|refund|(?:has|have|was|were)(?: been)? made|thank you for using");
    /** Loose verbs: count only with a card/account reference in the same sentence and no security/bill/balance cue. */
    private static final Pattern LOOSE_VERB = word("used|successful|successfully|processed|initiated");
    private static final Pattern CREDIT_VERB = word("credited|received|deposited");
    private static final Pattern DEBIT_VERB = word("debited|spent|withdrawn|paid|transferred|sent|used|charged");
    private static final Pattern REVERSAL_WORD = word("reversed|reversal|refunded|refund");
    private static final int TXN_WINDOW = 120;
    private static final int SHORT_LEAD_IN_CHARS = 30;

    /** A sentence with this wording is about a future, conditional or already-settled amount, not an event. */
    private static final Pattern FUTURE_GUARD = Pattern.compile(
            "\\bwill\\b|\\bwould\\b(?! like to)|\\bto be (?:debited|charged|deducted)\\b|\\b(?:is|are|was) due\\b|\\bdue (?:on|by|date)\\b"
                    + "|\\b(?:payment|amount|minimum|total) due\\b|\\bscheduled\\b|\\bupcoming\\b"
                    + "|\\b(?:pay|payable)\\b.{0,60}?\\b(?:by|before)\\b|\\balready (?:paid|made)\\b");
    /** Hypothetical or illustrative wording: an example amount is never a transaction. */
    private static final Pattern NON_EVENT_GUARD = Pattern.compile(
            "\\bexamples?\\b|\\be\\.g\\b|\\bfor instance\\b|\\bimagine\\b");
    /** Weaker non-event wording, tested only on the comma/semicolon clause around the amount ("... at SWIGGY, you earn 5 points"). */
    private static final Pattern CLAUSE_NON_EVENT_GUARD = Pattern.compile("\\bearns?\\b|\\byou could\\b");
    /** Newsletter or explainer furniture. A transaction sentence in such a mail needs its own card/account reference. */
    private static final Pattern NEWSLETTER = Pattern.compile(
            "\\bview the newsletter\\b|\\bcan.t display\\b|\\bfine print\\b|\\bread more\\b|\\bunsubscribe\\b");
    private static final int REF_NEIGHBOUR_CHARS = 200;
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
    /** Table labels without a colon: "Amount INR 2.00 Merchant X". Block boundaries (U+2029) count as whitespace. */
    private static final String GAP = "[\\s" + EmailText.SECTION + "]*";
    private static final String CUR = "(?:\\b(?:inr|usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|hkd)|[$\u20AC\u00A3])\\s?\\d";
    private static final Pattern LABELLED_AMOUNT_NO_COLON = Pattern.compile(
            "(?:^|[.!?:" + EmailText.SECTION + "])" + GAP + "(?:transaction amount|txn amount|amount|amt)" + GAP + CUR);
    private static final Pattern TRANSACTION_LABEL_NO_COLON = Pattern.compile(
            "(?:[.!?:" + EmailText.SECTION + "]|\\d)" + GAP + "(?:merchant name|merchant|transaction date|txn date|date|card)[\\s" + EmailText.SECTION + "]+\\S");
    /** The future wording that makes a labelled summary a reminder, not an event (subject or body). */
    private static final Pattern LABELLED_FUTURE_GUARD = Pattern.compile(
            "\\bto be (?:debited|charged|deducted)\\b|\\bwill be (?:debited|charged|deducted)\\b|\\bupcoming\\b|\\bscheduled\\b");
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
    /** Security cues used only in step 4. They do not stop a real transaction sentence from being an alert. */
    private static final Pattern SECURITY_EXTRA = Pattern.compile(
            "\\blog(?:ged)? on\\b|\\bbiometric\\b|\\bdevice\\b|\\bpayee\\b|\\bmpin\\b|\\bsecure usage tips\\b|\\btwo[- ]step verification\\b|\\b2[- ]step verification\\b");
    private static final Pattern SERVICE = Pattern.compile(
            "\\bkyc\\b|\\b(?:profile|address|e-?mail|mobile(?: number)?|contact details)\\b[^.]{0,40}?\\b(?:updated|changed|registered|verified)\\b"
                    + "|\\brequest (?:has been |is )?(?:received|registered|logged)\\b|\\bwelcome (?:to|aboard)\\b"
                    + "|\\bfeedback\\b|would love to hear from you|\\bsurvey\\b|scheduled maintenance|relationship manager|\\bmissed call\\b"
                    + "|credit card is on its way|thank you for choosing|dedicated point of contact"
                    + "|query reference.{0,40}?\\bregistered\\b|\\bfinancial results\\b|\\brefund\\b.{0,60}?\\bprocessed\\b");
    /** Request-style service cues; they never apply when the mail also carries a labelled transaction with a card/account ref. */
    private static final Pattern SERVICE_REQUEST = Pattern.compile(
            "\\brequest\\b.{0,80}?\\binitiated\\b|\\bconvert\\b.{0,60}?\\bemi\\b|\\bsip\\b.{0,80}?\\binitiated\\b");
    /** "initiated" is not a transaction verb in these contexts (EMI conversion, SIP, mandates, requests). */
    private static final Pattern INITIATED_EXCLUSION = Pattern.compile(
            "\\b(?:emi|sip|mandate|standing instruction|request|registration|conversion|convert)\\b");
    private static final Pattern STRONG_PAST_VERB = word("debited|credited|spent|withdrawn|received|refunded|reversed");
    /** Replies, tickets and auto acknowledgements: whatever amounts they quote, they are never alerts. */
    private static final Pattern SUPPORT_SUBJECT = Pattern.compile(
            "^\\s*(?:re|fwd?)\\s*:|^\\s*<auto>|service request|\\bticket\\b|query reference|auto acknowledgement|\\bescalation\\b");
    private static final Pattern SUPPORT_BODY = Pattern.compile("we acknowledge the receipt of your email|thank you for writing to us");
    /** Selling wording that wins over the weak balance/bill cues, but only when no card/account is referenced. */
    private static final Pattern STRONG_PROMO = Pattern.compile(
            "\\blounge\\b|reduce your (?:card )?bill|\\bvoucher\\b|\\bearned\\b.{0,40}?\\bcashback\\b");
    private static final Pattern PROMOTION = Pattern.compile(
            "\\bunsubscribe\\b|(?<!unsolicited )\\boffers?\\b|\\bview the newsletter\\b|\\bcan.t display\\b|\\bfine print\\b|\\bread more\\b|\\bapply now\\b|\\bpre-?approved\\b|\\blimited period\\b|\\bt&c\\b|\\bterms (?:and|&) conditions\\b|(?<!\\d)\\d{1,3}%\\s*off|\\bexclusive\\b");

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

        String attachmentKind = statementAttachmentKind(message.attachments());

        List<String> sentences = sentences(email.text());
        boolean hasAmount = AMOUNT.matcher(lower).find();
        String bodyLower = bodyOf(lower, subjectLower);
        boolean newsletter = NEWSLETTER.matcher(bodyLower).find();
        String subjectNorm = EmailText.normalise(message.subject()).toLowerCase(Locale.ROOT);
        TransactionSentence txn = hasAmount ? findTransactionSentence(sentences, newsletter, subjectNorm, bodyLower) : null;

        // 0. Support replies, tickets and acknowledgements: never an alert, never a statement. A real transaction
        // sentence with its own card/account reference, or a statement attachment with statement words, wins.
        boolean realTransaction = txn != null && ACCOUNT_REF.matcher(txn.lowerSentence()).find();
        boolean statementAttachment = attachmentKind != null
                && STATEMENT_WORDS.matcher(subjectLower + attachmentNames(message)).find();
        if ((SUPPORT_SUBJECT.matcher(subjectLower).find() || SUPPORT_BODY.matcher(lower).find())
                && !realTransaction && !statementAttachment) {
            signals.add("kw:support");
            return result(attachmentKind != null ? EmailSubtype.DOCUMENT : EmailSubtype.SERVICE, ClassifierConfidence.MEDIUM, bankId, signals);
        }

        // 1. Statement attachment
        if (attachmentKind != null) {
            signals.add("att:" + attachmentKind);
            return classifyAttachment(message, lower, subjectLower, bankId, signals);
        }

        // 2. OTP: a real OTP subject, or the otp token and its digits in one sentence that is no transaction sentence
        if (OTP_SUBJECT.matcher(subjectLower).find()
                || (OTP_SUBJECT_ENDING.matcher(subjectLower).find() && OTP_CODE_NEARBY.matcher(lower).find())
                || hasOtpSentence(sentences)) {
            signals.add("otp");
            return result(EmailSubtype.OTP, ClassifierConfidence.HIGH, bankId, signals);
        }

        // 3. Transaction sentence
        if (hasAmount) {
            signals.add("amt");
        }
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
        boolean labelledAllowed = hasAmount && !declined && ACCOUNT_REF.matcher(lower).find()
                && !labelledFuture(sentences, subjectLower)
                && !(newsletter && !ACCOUNT_REF.matcher(bodyLower).find());
        if (labelledAllowed && hasLabelledTransaction(sentences, lower)) {
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
        if (!ACCOUNT_REF.matcher(bodyLower).find() && STRONG_PROMO.matcher(lower).find()) {
            signals.add("promo");
            return result(EmailSubtype.PROMOTION, ClassifierConfidence.MEDIUM, bankId, signals);
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
        // Statement words in the subject and no attachment: a notice, even when it talks about the PDF password.
        if (STATEMENT_WORDS.matcher(subjectLower).find()) {
            signals.add("kw:statement");
            return result(EmailSubtype.STATEMENT_NOTICE, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        if (SECURITY.matcher(lower).find() || SECURITY_EXTRA.matcher(lower).find()) {
            signals.add("kw:security");
            return result(EmailSubtype.SECURITY, ClassifierConfidence.MEDIUM, bankId, signals);
        }
        boolean refTransaction = hasAmount && ACCOUNT_REF.matcher(lower).find() && hasLabelledTransaction(sentences, lower);
        if (SERVICE.matcher(lower).find() || (!refTransaction && SERVICE_REQUEST.matcher(lower).find())) {
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
        String head = subjectLower + attachmentNames(message);
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

    private static String attachmentNames(GmailMessage message) {
        StringBuilder names = new StringBuilder();
        if (message.attachments() != null) {
            for (GmailAttachment att : message.attachments()) {
                if (att != null && att.filename() != null) {
                    names.append(' ').append(FILENAME_SEPARATORS.matcher(att.filename().toLowerCase(Locale.ROOT)).replaceAll(" "));
                }
            }
        }
        return names.toString();
    }

    /**
     * The future wording that makes a labelled summary a reminder: in the subject, in the sentence holding the
     * labelled amount or the one before it, or in a following "Label: value" fragment. Not a footer.
     */
    private static boolean labelledFuture(List<String> sentences, String subjectLower) {
        if (LABELLED_FUTURE_GUARD.matcher(subjectLower).find()) {
            return true;
        }
        for (int i = 0; i < sentences.size(); i++) {
            String own = sentences.get(i).toLowerCase(Locale.ROOT);
            if (!LABELLED_AMOUNT.matcher(own).find() && !LABELLED_AMOUNT_NO_COLON.matcher(own).find()) {
                continue;
            }
            if (LABELLED_FUTURE_GUARD.matcher(own).find()
                    || (i > 0 && LABELLED_FUTURE_GUARD.matcher(sentences.get(i - 1).toLowerCase(Locale.ROOT)).find())) {
                return true;
            }
            if (i + 1 < sentences.size()) {
                String next = sentences.get(i + 1).toLowerCase(Locale.ROOT);
                int colon = next.indexOf(':');
                if (colon >= 0 && colon <= 40 && LABELLED_FUTURE_GUARD.matcher(next).find()) {
                    return true;
                }
            }
        }
        return false;
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

    /** The text after the subject section; the whole text when the subject is blank. */
    private static String bodyOf(String lower, String subjectLower) {
        if (subjectLower.isBlank()) {
            return lower;
        }
        int cut = lower.indexOf(EmailText.SECTION);
        return cut < 0 ? "" : lower.substring(cut + 1);
    }

    private static boolean isGuarded(String lower) {
        if (FUTURE_GUARD.matcher(lower).find() || PROMO_SENTENCE_GUARD.matcher(lower).find() || NON_EVENT_GUARD.matcher(lower).find()) {
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
    private static TransactionSentence findTransactionSentence(List<String> sentences, boolean newsletter, String subjectNorm, String bodyLower) {
        for (int index = 0; index < sentences.size(); index++) {
            String lower = sentences.get(index).toLowerCase(Locale.ROOT);
            if (isGuarded(lower)) {
                continue;
            }
            // Cashback / reward news without a card or account in the sentence is a promotion, not a credit.
            if (CASHBACK.matcher(lower).find() && !ACCOUNT_REF.matcher(lower).find()) {
                continue;
            }
            // In a newsletter the sentence needs its own reference (itself or ~200 chars around, subject excluded),
            // or a strong past verb plus a reference anywhere in the body.
            boolean refNearby = !newsletter || hasRefNearby(sentences, index, subjectNorm);
            boolean bodyRef = newsletter && ACCOUNT_REF.matcher(bodyLower).find();
            boolean looseAllowed = ACCOUNT_REF.matcher(lower).find()
                    && !SECURITY.matcher(lower).find() && !BILL_DUE.matcher(lower).find() && !BALANCE.matcher(lower).find();
            Matcher amount = AMOUNT.matcher(lower);
            while (amount.find()) {
                if (clauseIsNonEvent(lower, amount.start(), amount.end())) {
                    continue;
                }
                int from = Math.max(0, amount.start() - TXN_WINDOW);
                int to = Math.min(lower.length(), amount.end() + TXN_WINDOW);
                List<String> verbs = new ArrayList<>();
                collectVerbs(TXN_VERB, lower, from, to, verbs);
                if (looseAllowed) {
                    collectVerbs(LOOSE_VERB, lower, from, to, verbs);
                }
                if (verbs.contains("initiated") && INITIATED_EXCLUSION.matcher(lower.substring(from, to)).find()) {
                    verbs.remove("initiated");
                }
                if (newsletter && !refNearby && !(bodyRef && STRONG_PAST_VERB.matcher(String.join(" ", verbs)).find())) {
                    continue;
                }
                if (!verbs.isEmpty()) {
                    return new TransactionSentence(verbs, lower, index);
                }
            }
        }
        return null;
    }

    /** The comma/semicolon clause around the amount holds weak non-event wording ("earns", "you could"). */
    private static boolean clauseIsNonEvent(String lower, int amountStart, int amountEnd) {
        int start = Math.max(lower.lastIndexOf(',', amountStart), lower.lastIndexOf(';', amountStart)) + 1;
        int commaEnd = lower.indexOf(',', amountEnd);
        int semiEnd = lower.indexOf(';', amountEnd);
        int end = commaEnd < 0 ? (semiEnd < 0 ? lower.length() : semiEnd) : (semiEnd < 0 ? commaEnd : Math.min(commaEnd, semiEnd));
        return CLAUSE_NON_EVENT_GUARD.matcher(lower.substring(start, end)).find();
    }

    private static boolean hasRefNearby(List<String> sentences, int index, String subjectNorm) {
        String own = sentences.get(index).toLowerCase(Locale.ROOT);
        StringBuilder window = new StringBuilder();
        if (index > 0) {
            String before = sentences.get(index - 1).toLowerCase(Locale.ROOT);
            window.append(before, Math.max(0, before.length() - REF_NEIGHBOUR_CHARS), before.length()).append(' ');
        }
        window.append(own).append(' ');
        if (index + 1 < sentences.size()) {
            String after = sentences.get(index + 1).toLowerCase(Locale.ROOT);
            window.append(after, 0, Math.min(after.length(), REF_NEIGHBOUR_CHARS));
        }
        String text = window.toString();
        if (!subjectNorm.isEmpty()) {
            text = text.replace(subjectNorm, " ");
        }
        return ACCOUNT_REF.matcher(text).find();
    }

    private static void collectVerbs(Pattern pattern, String lower, int from, int to, List<String> verbs) {
        Matcher verb = pattern.matcher(lower).region(from, to).useTransparentBounds(true);
        while (verb.find()) {
            if (!verbs.contains(verb.group())) {
                verbs.add(verb.group());
            }
        }
    }

    private static boolean hasLabelledTransaction(List<String> sentences, String lowerWhole) {
        if (LABELLED_AMOUNT_NO_COLON.matcher(lowerWhole).find() && TRANSACTION_LABEL_NO_COLON.matcher(lowerWhole).find()) {
            return true;
        }
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
