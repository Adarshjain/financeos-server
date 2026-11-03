package com.financeos.gmail.accountdetect.scan;

import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.time.Instant;
import java.util.List;

/**
 * One scanned mail: its derived features, the statement content read from its open PDFs, and what happened to each
 * statement PDF. Held in memory; only derived data is ever stored.
 *
 * @param role         the role of the mail's layout cluster at scan time
 * @param bodyFetched  the full body was read (not only headers)
 * @param pdfs         statement-role PDFs seen on this mail, in attachment order
 * @param headerVote   the mail's role vote from its headers alone (what the scan's cluster-role vote counts)
 * @param incomplete   a read failed (headers, a wanted body, or a statement PDF download): the facts are partial
 */
public record ScannedMail(String messageId, Instant internalDate, String senderDomain, Role role, MailDerived derived,
                          boolean bodyFetched, List<StatementContent> statements, List<Pdf> pdfs, Role headerVote, boolean incomplete) {

    public ScannedMail {
        statements = statements == null ? List.of() : List.copyOf(statements);
        pdfs = pdfs == null ? List.of() : List.copyOf(pdfs);
        if (headerVote == null && derived != null) headerVote = derived.roleVote();
    }

    /** A complete mail whose header vote is its derived vote. */
    public ScannedMail(String messageId, Instant internalDate, String senderDomain, Role role, MailDerived derived,
                       boolean bodyFetched, List<StatementContent> statements, List<Pdf> pdfs) {
        this(messageId, internalDate, senderDomain, role, derived, bodyFetched, statements, pdfs, null, false);
    }

    /** A listed mail whose headers could not be read: nothing is known of it but its id. */
    public static ScannedMail headersMissing(String messageId) {
        return new ScannedMail(messageId, null, null, null, null, false, List.of(), List.of(), null, true);
    }

    /**
     * @param status OPENED, PROTECTED, FAILED, NO_TEXT, NO_BANK ({@link com.financeos.gmail.accountdetect.StatementPdfReader.Status}),
     *               {@link #UNLOCKED}, {@link #UNLOCK_FAILED} or {@link #DOWNLOAD_FAILED}
     * @param unlockRule the rule tokens (or "SAVED") that opened a protected PDF; never the password
     * @param unlockHint the password rules the mail itself named (tokens only, from a protected PDF's mail), or null
     */
    public record Pdf(String attachmentId, String status, String unlockRule, String unlockHint) {
        public Pdf(String attachmentId, String status) {
            this(attachmentId, status, null, null);
        }
    }

    /** A protected statement PDF that a password rule opened. */
    public static final String UNLOCKED = "UNLOCKED";
    /** A protected statement PDF that was tried with at least one password and stayed closed. */
    public static final String UNLOCK_FAILED = "UNLOCK_FAILED";

    public static final String DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    public boolean hasProtectedPdf() {
        return pdfs.stream().anyMatch(p -> "PROTECTED".equals(p.status()));
    }
}
