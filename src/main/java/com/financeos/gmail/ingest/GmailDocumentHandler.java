package com.financeos.gmail.ingest;

import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.internal.GmailMessage;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads one family of mails (NPS statements and alerts) that are documents, not bank transaction alerts. The
 * ingestion asks each handler right after the sender check: a mail a handler takes never becomes a bank
 * transaction and never reaches the AI read.
 */
public interface GmailDocumentHandler {

    /** What a handler did with one mail: the status the ledger row ends in and, optionally, why. */
    record Handled(GmailProcessedStatus status, @Nullable String error) {
        public static Handled of(GmailProcessedStatus status) {
            return new Handled(status, null);
        }
    }

    /** Whether the handler takes mail from this (lower-cased) address. */
    boolean supports(String fromAddress, GmailMessage message);

    /** Handles the mail inside the ingestion's per-message transaction; a thrown exception retries the mail later. */
    Handled handle(GmailConnection connection, GmailMessage message);

    /** The exact sender addresses (lower-cased) the handler needs in the user's allowlist. */
    List<String> senderAddresses();

    /** The name shown for those senders. */
    String senderName();

    /** The oldest date their Gmail backfill reaches back to. */
    LocalDate backfillFrom();
}
