package com.financeos.gmail.accountdetect.scan;

import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.internal.GmailMessage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where the account-detect scan reads mail from: Gmail in the app and the dev runner, a fake in tests. An expired or
 * revoked Gmail grant surfaces as a {@link com.financeos.gmail.internal.GmailEngineException} with
 * {@link com.financeos.gmail.internal.GmailError#AUTH_ERROR}. {@code out} receives report lines (counts only).
 */
public interface MailSource {

    /** Ids of the mail from these sender tokens (deduplicated, in token order), only mail after {@code after} when set. */
    Listing list(Collection<String> tokens, Instant after) throws Exception;

    /** Header features (sender domain, date, subject, snippet; no body) by id, in {@code ids} order; misses are left out. */
    Map<String, MailFeature> metadata(List<String> ids, Consumer<String> out) throws Exception;

    /** Full messages (body and attachment names) by id; gone or failed ones are left out. */
    Map<String, GmailMessage> full(List<String> ids, Consumer<String> out) throws Exception;

    /** A paced attachment reader for one stage; {@code get} returns null when the download failed. */
    Attachments attachments();

    interface Attachments {
        byte[] get(String messageId, String attachmentId);
    }

    record Listing(List<String> ids, int calls) {}
}
