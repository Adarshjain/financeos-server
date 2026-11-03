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
 */
public record ScannedMail(String messageId, Instant internalDate, String senderDomain, Role role, MailDerived derived,
                          boolean bodyFetched, List<StatementContent> statements, List<Pdf> pdfs) {

    public ScannedMail {
        statements = statements == null ? List.of() : List.copyOf(statements);
        pdfs = pdfs == null ? List.of() : List.copyOf(pdfs);
    }

    /**
     * @param status OPENED, PROTECTED, FAILED, NO_TEXT, NO_BANK ({@link com.financeos.gmail.accountdetect.StatementPdfReader.Status})
     *               or {@link #DOWNLOAD_FAILED}
     */
    public record Pdf(String attachmentId, String status) {}

    public static final String DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    public boolean hasProtectedPdf() {
        return pdfs.stream().anyMatch(p -> "PROTECTED".equals(p.status()));
    }
}
