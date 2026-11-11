package com.financeos.sms.read;

import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.sms.line.SenderCatalogue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one message. Order: explicit not-a-transaction classes first (they win over any template), then the template of
 * its sender, then the generic reader (needs the owner's confirmation). Pure: no state, no I/O.
 */
@Component
public class SmsReader {

    private static final int MAX_REF = 60;
    private static final int MAX_PARTY = 200;

    // ------------------------------------------------------------------ not a transaction

    private record NotTxn(SmsKind kind, Pattern pattern) {}

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static final List<NotTxn> CLASSES = List.of(
            new NotTxn(SmsKind.OTP, ci("\\botp\\b|one[\\s-]*time[\\s-]*(password|passcode|pin)|verification\\s+code|do\\s+not\\s+share")),
            new NotTxn(SmsKind.FAILED, ci("\\b(declined|failed|failure|unsuccessful|unsuccessfully|not\\s+successful|could\\s+not\\s+be\\s+processed)\\b")),
            new NotTxn(SmsKind.FUTURE, ci("\\b(will|shall)\\s+be\\s+(debited|credited|deducted|charged)\\b")),
            new NotTxn(SmsKind.REQUEST, ci("\\b(collect|autopay|auto[\\s-]?pay|mandate)\\s+request\\b|\\bhas\\s+requested\\s+money\\b|\\brequested\\s+money\\b")),
            new NotTxn(SmsKind.MANDATE, ci("mandate[^.]{0,80}\\b(created|set\\s*up|registered|revoked|cancell?ed|paused|activated|approved|modified)\\b"
                    + "|\\b(created|revoked|cancell?ed|registered|set\\s*up)\\b[^.]{0,40}mandate"
                    + "|\\bauto[\\s-]?pay\\s+(activation|activated|set\\s*up|registration)\\b|\\bmax(imum)?\\s+limit\\b")),
            new NotTxn(SmsKind.SENDER_SIDE_CONFIRMATION, ci("\\bcredited\\s+to\\s+the\\b.{0,80}?\\bA/c\\b")),
            new NotTxn(SmsKind.REFUND_INITIATED, ci("\\brefund\\b.{0,80}?\\bhas\\s+been\\s+initiated\\b|\\brefund\\s+initiated\\b")));

    /**
     * Broad word lists: they run only when no template matched, so a merchant or payee called "Offer Mart" or
     * "Statement Store" cannot turn a template debit into IGNORED.
     */
    private static final List<NotTxn> BROAD_CLASSES = List.of(
            new NotTxn(SmsKind.STATEMENT_DUE, ci("\\bstatement\\b|\\bamount\\s+due\\b|\\bdue\\s+(date|on|by)\\b|\\boutstanding\\b|\\bminimum\\s+due\\b|\\btotal\\s+due\\b")),
            new NotTxn(SmsKind.OFFER, ci("\\bpre[\\s-]?approved\\b|\\bloan\\s+(offer|up\\s+to)\\b|\\blimit\\s+(has\\s+been\\s+)?(increased|enhanced|revised|changed|reduced)\\b"
                    + "|\\boffers?\\b|\\bcongratulations\\b|\\bapply\\s+now\\b|\\bcredit\\s+limit\\b")),
            // A card's credit balance (a refund owed to the holder) is a notice, not a payment.
            new NotTxn(SmsKind.NOT_A_TRANSACTION, ci("\\bcredit\\s+balance\\s+of\\b")));

    /** Per-sender "not a transaction" rows of the format list. */
    private static final Map<String, Pattern> SENDER_NOT_TXN = new LinkedHashMap<>();

    static {
        Pattern hsbc = ci("log[\\s-]?on|log[\\s-]?in|logged\\s+in|secure\\s*key|\\bdevice\\b|maintenance|added\\s+HSBC\\s+Bank\\s+Credit\\s+Card|transaction\\s+limit");
        SENDER_NOT_TXN.put("HSBCIN", hsbc);
        SENDER_NOT_TXN.put("HSBCIM", hsbc);
        SENDER_NOT_TXN.put("HPPAYS", ci("congratulations|happy\\s+coins|cashback"));
        SENDER_NOT_TXN.put("PHONPE", ci("registration|registered|\\bupi\\s*id\\b"));
        SENDER_NOT_TXN.put("CREDWL", ci("beneficiary"));
        SENDER_NOT_TXN.put("MOBIKW", ci("log[\\s-]?in|logged|password|successfully\\s+received\\s+for\\s+your"));
        SENDER_NOT_TXN.put("PAYZAP", ci("activat|deactivat|bank\\s+account\\s+(has\\s+been\\s+)?added|account\\s+added"));
        Pattern paytm = ci("\\b(log[\\s-]?in|logged|password|kyc|inactive|dormant)\\b");
        SENDER_NOT_TXN.put("IPAYTM", paytm);
        SENDER_NOT_TXN.put("PYTMBK", paytm);
        SENDER_NOT_TXN.put("PAYTMB", paytm);
        Pattern meal = ci("expir|activat|\\bpin\\b");
        SENDER_NOT_TXN.put("PLUXEE", meal);
        SENDER_NOT_TXN.put("SODEXO", meal);
    }

    // ------------------------------------------------------------------ templates

    private record Template(String key, Set<String> senders, TransactionType direction, Pattern pattern) {}

    private static final String CUR = "(?:(?:INR|Rs\\.?|₹)\\s*)";
    private static final String AMT = "(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)";
    private static final String BAL = "(?<bal>\\d[\\d,]*(?:\\.\\d{1,2})?)";
    private static final String TIME = "(?:\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?)";
    private static final String REF = "(?<ref>[A-Za-z0-9]+)";
    private static final String CP = "(?<cp>.+?)";
    private static final String HSBC_ACCT = "A/c \\d{3}-\\d{3}\\*+-(?<end>\\d{3}) ";
    private static final String DDMON = "(?<dt>\\d{1,2}[A-Za-z]{3})\\b";
    private static final String DMY_NAMED = "(?<dt>\\d{1,2}-[A-Za-z]{3}-\\d{2,4})";
    private static final String DMY_NUM = "(?<dt>\\d{1,2}-\\d{1,2}-\\d{4})";
    /** Time with dots (HSBC credits) and time whose seconds may be one digit (some meal card texts). */
    private static final String TIME_DOT = "(?:\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?)";
    private static final String TIME_S = "(?:\\d{1,2}:\\d{2}(?::\\d{1,2})?)";
    /** Templates whose counterparty is a fixed label, not text of the message. */
    private static final Map<String, String> FIXED_PARTY = Map.of("hsbc_interest_credit", "Credit interest");

    private static final List<Template> TEMPLATES = new ArrayList<>();

    private static void t(String key, String senders, TransactionType direction, String regex) {
        // Every literal space tolerates any spacing (or none); placeholders carry their own pattern.
        String r = regex.replace(" ", "\\s*").replace("{cur}", CUR).replace("{amt}", AMT).replace("{bal}", BAL)
                .replace("{t}", TIME).replace("{ts}", TIME_S).replace("{td}", TIME_DOT).replace("{ref}", REF).replace("{cp}", CP);
        TEMPLATES.add(new Template(key, Set.of(senders.split(",")), direction, ci(r)));
    }

    static {
        TransactionType d = TransactionType.DEBIT;
        TransactionType c = TransactionType.CREDIT;
        // HSBC bank account
        t("hsbc_acct_debit", "HSBCIN", d, HSBC_ACCT + "has been debited with {cur}{amt}-? on " + DDMON + ".*?Avl Bal is {cur}?{bal}");
        t("hsbc_upi_debit", "HSBCIN", d, "{cur}{amt} is paid from HSBC account X+(?<end>\\d{4}) to {cp} on " + DMY_NAMED + " with ref {ref}");
        t("hsbc_neft_debit", "HSBCIN", d, HSBC_ACCT + "is debited with {cur}{amt}-? on " + DDMON
                + " with UTR {ref} as (?:NEFT|RTGS|IMPS) via Online to .+? A/c \\*\\*\\*\\d{4} {cp}\\s*\\.\\s*Avl Bal is {cur}{bal}");
        t("hsbc_debit_card", "HSBCIN", d, "HSBC Debit Card X+(?<end>\\d{4}) for {cur}{amt} on " + DDMON
                + " at {cp}\\s*\\.\\s*Your available bal is {cur}{bal}");
        t("hsbc_upi_credit", "HSBCIN", c, "HSBC Acc X+(?<end>\\d{4}) is credited for {cur}{amt} on " + DMY_NAMED
                + " from (?<cp>\\S+?)\\s*\\.\\s*UPI Ref No {ref}");
        t("hsbc_neft_credit", "HSBCIN", c, HSBC_ACCT + "is credited with {cur}{amt}\\+? on " + DDMON
                + " at {t} with UTR {ref} as NEFT from .+? A/c \\*\\*\\*\\d{4} of {cp}\\s*\\.\\s*Your Avl Bal is {cur}{bal}");
        t("hsbc_imps_credit", "HSBCIN", c, HSBC_ACCT + "is credited with {cur}{amt}\\+? on " + DDMON
                + " at {t} with UTR {ref} as IMPS from .+? A/c of {cp}\\s*\\.\\s*Your Avl Bal is {cur}{bal}");
        t("hsbc_utr_credit", "HSBCIN", c, HSBC_ACCT + "is credited with {cur}{amt}\\+? on " + DDMON
                + " at {td} with UTR {ref} as (?:NEFT|RTGS|IMPS) from .+? A/c (?:\\*+\\d{4} )?of {cp}\\s*\\.\\s*Your Avl Bal is {cur}{bal}");
        t("hsbc_interest_credit", "HSBCIN", c, "your HSBC A/c \\d{3}-\\d{3}\\*+-(?<end>\\d{3}) has been credited with {cur}{amt}\\+? on " + DDMON
                + " as CREDIT INTEREST\\s*\\.\\s*Your available Bal is {cur}?{bal}");
        // HP Pay
        t("hp_debit", "HPPAYS", d, "Transaction of {cur}{amt} for purchase of {cp} is successful");
        t("hp_voucher_credit", "HPPAYS", c, "Add Voucher request of {cur}{amt} has been processed successfully and your current HP Pay wallet balance is {cur}?{bal}");
        t("hp_recharge_credit", "HPPAYS", c, "Wallet Recharge .*?Order ID {ref}, initiated on (?<dt>\\d{1,2}/\\d{1,2}/\\d{4}) at {t}"
                + " was successful and your Wallet Account has been recharged with {cur}{amt}\\s*\\.?\\s*Your current HP PAY balance is {cur}{bal}");
        // Amazon Pay wallet
        t("apay_debit_ref", "JUSPAY,QCAMZN", d, "Apay Wallet balance is debited for {cur}{amt}\\s*\\.\\s*Reference Number is {ref}");
        t("apay_debit_bal_a", "JUSPAY,QCAMZN", d, "Payment of {cur}{amt} using Apay Balance successful at {cp}\\s*\\.\\s*Updated Balance: {cur}{bal}");
        t("apay_debit_bal_b", "JUSPAY,QCAMZN", d, "Payment of {cur}{amt} using Apay balance is successful at {cp}\\s*\\.\\s*Updated balance is {cur}{bal}");
        // PhonePe gift card
        t("phonepe_gift_debit", "PHONPE", d, "You[’']ve paid {cur}{amt} via PhonePe gift card to {cp} on (?<dt>[A-Za-z]{3} \\d{1,2}, \\d{4}) at {t}"
                + "\\s*\\.\\s*Not you.*?Remaining balance {cur}{bal}");
        // CRED wallet
        t("cred_paid_a", "CREDWL", d, "You have successfully paid {cur}{amt} and your updated wallet balance is {cur}{bal}");
        t("cred_paid_b", "CREDWL", d, "Your payment of {cur}{amt}, made with CRED wallet, was successful\\s*\\.\\s*Your updated CRED wallet balance is {cur}{bal}");
        t("cred_credit", "CREDWL", c, "{cur}{amt} has been successfully credited to your CRED wallet\\s*\\.\\s*Your updated CRED wallet balance is {cur}{bal}");
        // MobiKwik
        t("mobikwik_debit", "MOBIKW", d, "{cur}{amt} has been debited from your MobiKwik wallet\\s*\\.\\s*Remaining balance:? {cur}{bal}");
        // PayZapp
        t("payzapp_debit", "PAYZAP", d, "{cur}{amt} has been debited from your PayZapp wallet (?<end>\\d{4})\\s*\\.\\s*Avl Bal:? {cur}{bal}");
        t("payzapp_received", "PAYZAP", c, "You have received {cur}{amt} from PayZapp user {cp}\\s*\\.\\s*Available Balance:? {cur}{bal}");
        t("payzapp_transfer", "PAYZAP", d, "You have transferred {cur}{amt} to PayZapp user {cp}\\s*\\.\\s*Available Balance:? {cur}{bal}");
        // Paytm
        t("paytm_balance", "IPAYTM,PYTMBK,PAYTMB", d, "Paid {cur}{amt} to {cp} from Paytm Balance");
        t("paytm_ppbl", "IPAYTM,PYTMBK,PAYTMB", d, "Paid {cur}{amt} to {cp} from Paytm Payments Bank Wallet");
        t("paytm_ref", "IPAYTM,PYTMBK,PAYTMB", d, "Paid {cur}{amt} to {cp} with Paytm Ref:? {ref}");
        // Pluxee and Sodexo (the same card before the rename)
        t("pluxee_spent_a", "PLUXEE,SODEXO", d, "{cur}{amt} was spent from Meal Wallet linked to your Pluxee Card x+(?<end>\\d{4}) on " + DMY_NUM
                + " {t} at {cp}\\s*\\.\\s*Txn no\\.?\\s*{ref}\\s*\\.\\s*Avl bal is {cur}{bal}");
        t("pluxee_spent_b", "PLUXEE,SODEXO", d, "{cur}{amt} spent from Pluxee Meal wallet, card no\\.?\\s*x+(?<end>\\d{4}), on " + DMY_NUM
                + " {t} at {cp}\\s*\\.\\s*Avl bal {cur}{bal}");
        t("pluxee_credit", "PLUXEE,SODEXO", c, "Your Pluxee Card has been successfully credited with {cur}{amt} towards Meal Wallet on "
                + "(?<dt>[A-Za-z]{3} [A-Za-z]{3} \\d{1,2} \\d{4}) {t}\\s*\\.?\\s*Your current Meal Wallet balance is {cur}{bal}");
        t("sodexo_spent", "PLUXEE,SODEXO", d, "{cur}{amt} was spent from your Sodexo Meal A/c x+(?<end>\\d{4}) on " + DMY_NUM
                + " {t} at {cp}\\s*\\.\\s*Txn no\\.?\\s*{ref}\\s*\\.\\s*Avl bal is {cur}{bal}");
        t("sodexo_credit", "PLUXEE,SODEXO", c, "Your Sodexo Card has been successfully loaded with {cur}{amt} towards Meal A/c on {t}\\s*,\\s*"
                + "(?<dt>\\d{1,2} [A-Za-z]{3})\\s*\\.?\\s*Current Meal A/c bal is {cur}{bal}");
        t("pluxee_spent_c", "PLUXEE,SODEXO", d, "{cur}{amt} spent from Pluxee Meal wallet, card no\\.?\\s*x+(?<end>\\d{4}) on " + DMY_NUM
                + " {ts} at {cp}\\s*\\.\\s*Avl bal {cur}{bal}");
        t("pluxee_spent_d", "PLUXEE,SODEXO", d, "{cur}{amt} was spent from Meal Wallet linked to your Pluxee Card x+(?<end>\\d{4}) on " + DMY_NUM
                + " {ts} at {cp}\\s*\\.\\s*Txn no\\.?\\s*{ref}\\s*\\.\\s*Avl bal is {cur}{bal}");
        t("sodexo_spent_b", "PLUXEE,SODEXO", d, "{cur}{amt} was spent from your Sodexo Meal A/c x+(?<end>\\d{4}) on " + DMY_NUM
                + " {ts} at {cp}\\s*\\.\\s*Txn no\\.?\\s*{ref}\\s*\\.\\s*Avl bal is {cur}{bal}");
    }

    private static final Pattern PAYTM_WALLET_BAL = ci("Paytm\\s*Wallet\\s*-?\\s*" + CUR + "(?<bal>\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern PAYTM_VOUCHER_BAL = ci("Gift\\s*Voucher\\s*-?\\s*" + CUR + "(?<bal>\\d[\\d,]*(?:\\.\\d{1,2})?)");

    // ------------------------------------------------------------------ generic reader

    private static final Pattern G_AMOUNT = ci("(?:inr|rs\\.?|₹)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern G_DEBIT = ci("\\b(debited|spent|withdrawn|paid|purchase|deducted|sent)\\b");
    private static final Pattern G_CREDIT = ci("\\b(credited|received|deposited|refund(ed)?)\\b");
    /** Debit shapes without a debit word: a card "used", an IMPS / NEFT / UPI "payment of ... from A/c". */
    private static final Pattern G_CARD_USED = ci("card[^.,;]{0,30}?\\bused\\s+(?:at|for)\\b");
    private static final Pattern G_PAYMENT_FROM = ci("\\b(?:imps|neft|rtgs|upi)\\s+payment\\s+of\\s+(?:inr|rs\\.?|₹)\\s*(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)\\s+from\\s+A/c\\b");
    private static final String G_SLASH_DATE = "(?<dt>\\d{1,2}/\\d{1,2}/\\d{2,4})";
    private static final Pattern G_USED_AT = ci("\\bused\\s+at\\s+(?<cp>.+?)\\s+for\\s+" + CUR + "(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)\\s+on\\s+" + G_SLASH_DATE);
    private static final Pattern G_USED_FOR = ci("\\bused\\s+for\\s+" + CUR + "(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)\\s+at\\s+(?<cp>.+?)\\s+on\\s+" + G_SLASH_DATE);
    private static final Pattern G_DONE_TO = ci("\\bdone\\s+on\\s+(?<dt>\\d{1,2}-[A-Za-z]{3}-\\d{2,4})\\s+to\\s+(?<cp>.+?)\\s+is\\s+successful");
    /** Words that introduce a figure that is not the payment: a limit, a balance, an amount due. */
    private static final Pattern G_NOT_THE_PAYMENT = ci("\\b(?:limit|available|avl|bal|balance|due|outstanding|minimum)\\b\\W{0,8}(?:is|of)?\\W{0,3}$");
    /** Negative or unsettled wording: the direction of such a text is never trusted. */
    private static final Pattern G_UNSETTLED = ci("\\b(?:refund(?:ed)?|revers(?:ed|al)|failed|declined|unsuccessful(?:ly)?|will\\s+be|would\\s+be|to\\s+be\\s+(?:debited|credited)|request)\\b");
    private static final Pattern G_ENDING = ci("(?:x+|\\*+)[\\s-]*(\\d{3,4})(?!\\d)");
    private static final Pattern G_REF = ci("\\b(?:ref(?:erence)?(?:\\s*(?:no|number|id)\\.?)?|utr|txn\\s*(?:no|id)?|rrn)\\s*[:#.-]?\\s*([A-Za-z0-9]{6,})");
    private static final Pattern G_BAL = ci("\\bbal(?:ance)?\\s*(?:is|:)?\\s*(?:inr|rs\\.?|₹)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)");

    /** Largest number of integer digits an amount or balance may have (the columns are NUMBER(19,2)). */
    public static final int MAX_INTEGER_DIGITS = 13;

    /** Reads {@code body} sent by {@code sender} (raw or normalised header) and received at {@code receivedAt}. */
    public SmsReading read(String sender, String body, Instant receivedAt) {
        try {
            return doRead(sender, body, receivedAt);
        } catch (AmountTooLarge e) {
            return SmsReading.couldNotRead();
        }
    }

    private SmsReading doRead(String sender, String body, Instant receivedAt) {
        String core = SenderCatalogue.normaliseSender(sender);
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        LocalDate received = receivedAt.atZone(AppTime.zone()).toLocalDate();

        // Explicit classes win over everything, templates included.
        for (NotTxn cl : CLASSES) {
            if (cl.pattern().matcher(text).find()) {
                return SmsReading.ignored(cl.kind());
            }
        }
        for (Template tpl : TEMPLATES) {
            if (!tpl.senders().contains(core)) {
                continue;
            }
            Matcher m = tpl.pattern().matcher(text);
            if (m.find()) {
                return fromTemplate(tpl, m, text, received);
            }
        }
        // No template: the broad word lists and the per-sender rows, then the generic reader.
        for (NotTxn cl : BROAD_CLASSES) {
            if (cl.pattern().matcher(text).find()) {
                return SmsReading.ignored(cl.kind());
            }
        }
        Pattern own = SENDER_NOT_TXN.get(core);
        if (own != null && own.matcher(text).find()) {
            return SmsReading.ignored(SmsKind.NOT_A_TRANSACTION);
        }
        return generic(text, received);
    }

    /** An amount or balance with more digits than the columns hold. */
    private static final class AmountTooLarge extends RuntimeException {
        AmountTooLarge() {
            super(null, null, false, false);
        }
    }

    private SmsReading fromTemplate(Template tpl, Matcher m, String text, LocalDate received) {
        BigDecimal balance = group(m, "bal") == null ? null : money(group(m, "bal"));
        if (tpl.key().equals("paytm_balance")) {
            balance = paytmBalance(text);
        }
        LocalDate date = group(m, "dt") == null ? null : SmsDates.parse(group(m, "dt"), received);
        return new SmsReading(SmsKind.TRANSACTION, SmsReadStatus.READ, tpl.key(), money(m.group("amt")), tpl.direction(),
                group(m, "end"), cap(group(m, "ref"), MAX_REF), balance,
                FIXED_PARTY.containsKey(tpl.key()) ? FIXED_PARTY.get(tpl.key()) : cap(group(m, "cp"), MAX_PARTY),
                date != null ? date : received);
    }

    /** The wallet part of a Paytm balance line, else the gift-voucher part; null when neither shows. */
    private static BigDecimal paytmBalance(String text) {
        Matcher w = PAYTM_WALLET_BAL.matcher(text);
        if (w.find()) {
            return money(w.group("bal"));
        }
        Matcher v = PAYTM_VOUCHER_BAL.matcher(text);
        return v.find() ? money(v.group("bal")) : null;
    }

    private SmsReading generic(String text, LocalDate received) {
        Matcher amount = G_AMOUNT.matcher(text);
        String amountText = null;
        while (amount.find()) {
            // Never a figure a limit, balance or due amount introduces.
            String before = text.substring(Math.max(0, amount.start() - 25), amount.start());
            if (!G_NOT_THE_PAYMENT.matcher(before).find()) {
                amountText = amount.group(1);
                break;
            }
        }
        if (amountText == null) {
            return SmsReading.ignored(SmsKind.UNKNOWN);
        }
        Matcher debitWord = firstDebitWord(text);
        Matcher creditWord = firstWord(G_CREDIT, text);
        if (debitWord == null && creditWord == null) {
            return SmsReading.ignored(SmsKind.UNKNOWN);
        }
        // Both kinds of word (a debit and its credit leg in one text): the one that comes first is the direction.
        boolean debit = creditWord == null || (debitWord != null && debitWord.start() < creditWord.start());
        Matcher ending = G_ENDING.matcher(text);
        Matcher ref = G_REF.matcher(text);
        Matcher bal = G_BAL.matcher(text);
        String party = null;
        LocalDate date = received;
        if (debit) {
            for (Pattern shape : List.of(G_USED_AT, G_USED_FOR, G_DONE_TO, G_PAYMENT_FROM)) {
                Matcher m = shape.matcher(text);
                if (m.find()) {
                    if (shape != G_PAYMENT_FROM) {
                        party = cap(m.group("cp"), MAX_PARTY);
                        LocalDate parsed = SmsDates.parse(m.group("dt"), received);
                        date = parsed != null ? parsed : received;
                    }
                    String spent = group(m, "amt");
                    if (spent != null) {
                        amountText = spent;
                    }
                    if (shape != G_DONE_TO) {
                        break;
                    }
                }
            }
        }
        return new SmsReading(SmsKind.TRANSACTION, SmsReadStatus.NEEDS_CONFIRM, null, money(amountText),
                debit ? TransactionType.DEBIT : TransactionType.CREDIT, ending.find() ? ending.group(1) : null,
                ref.find() ? cap(ref.group(1), MAX_REF) : null, bal.find() ? money(bal.group(1)) : null, party, date,
                directionCertain(text));
    }

    /**
     * Whether the direction of a generic reading can be trusted, derived from the text alone. Not when the wording is
     * unsettled (refund, reversal, failed, will be, request ...), nor when a debit-type and a credit-type word both
     * appear and the first of them is not a plain "debited" / "credited".
     */
    public static boolean directionCertain(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (G_UNSETTLED.matcher(text).find()) {
            return false;
        }
        Matcher debitWord = firstDebitWord(text);
        Matcher creditWord = firstWord(G_CREDIT, text);
        if (debitWord == null || creditWord == null) {
            return true;
        }
        Matcher firstOne = debitWord.start() < creditWord.start() ? debitWord : creditWord;
        String word = firstOne.group().toLowerCase(java.util.Locale.ROOT);
        return word.equals("debited") || word.equals("credited");
    }

    private static Matcher firstDebitWord(String text) {
        Matcher best = null;
        for (Pattern p : List.of(G_DEBIT, G_CARD_USED, G_PAYMENT_FROM)) {
            Matcher m = firstWord(p, text);
            if (m != null && (best == null || m.start() < best.start())) {
                best = m;
            }
        }
        return best;
    }

    private static Matcher firstWord(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m : null;
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static BigDecimal money(String s) {
        BigDecimal v = new BigDecimal(s.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
        if (v.precision() - v.scale() > MAX_INTEGER_DIGITS) {
            throw new AmountTooLarge();
        }
        return v;
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
