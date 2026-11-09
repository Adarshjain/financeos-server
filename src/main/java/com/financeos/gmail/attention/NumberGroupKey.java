package com.financeos.gmail.attention;

import com.financeos.gmail.classify.BankSenderDomains;

import java.util.Locale;

/** How a mail's number is grouped: the bank it names (classifier, else the sender's domain) and its kind. */
public final class NumberGroupKey {

    private NumberGroupKey() {}

    /** Catalog bank id of the mail, or null when neither the classifier nor the sender's domain names one. */
    public static String bankOf(String classifiedBank, String senderAddress) {
        if (classifiedBank != null && !classifiedBank.isBlank()) {
            return classifiedBank.trim();
        }
        return BankSenderDomains.current().bankIdFor(senderAddress);
    }

    /** STATEMENT when the pipeline or the classifier read it as one, or its error talks about a statement; else ALERT. */
    public static String kindOf(String pipelineKind, String classifiedKind, String error) {
        if ("STATEMENT".equalsIgnoreCase(pipelineKind) || "STATEMENT".equalsIgnoreCase(classifiedKind)
                || (error != null && error.toLowerCase(Locale.ROOT).contains("statement"))) {
            return "STATEMENT";
        }
        return "ALERT";
    }

    /** True when both are the same bank, a missing bank matching only a missing bank. */
    public static boolean sameBank(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * False when the mail's bank and the account's bank are both known and differ: the mail belongs to another bank's
     * number. A partner brand's mail (fi, jupiter) belongs to its partner bank's accounts.
     */
    public static boolean attachable(String mailBank, String accountBank) {
        return mailBank == null || accountBank == null
                || com.financeos.gmail.accountdetect.BankPartners.covers(accountBank, mailBank);
    }
}
