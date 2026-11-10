package com.financeos.gmail.reconcile;

import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.MessageGoneException;
import org.springframework.stereotype.Component;

import com.google.api.services.gmail.Gmail;

import java.io.IOException;
import java.util.List;

/**
 * Finds, in an already-ingested Gmail message, the attachment whose sha256 is the one a statement was parsed from.
 * Uses the same engine calls and the same PDF/Excel filename rule as {@link StatementReconciliationService}, which it
 * leaves untouched. Other engine failures (auth, rate limit, network) propagate to the caller.
 */
@Component
public class StatementOriginalFetcher {

    public enum Kind { FOUND, MAIL_GONE, NO_MATCHING_ATTACHMENT }

    /** {@code bytes} and {@code name} are set only when found. */
    public record Result(Kind kind, byte[] bytes, String name) {}

    private final GmailEngine gmailEngine;

    public StatementOriginalFetcher(GmailEngine gmailEngine) {
        this.gmailEngine = gmailEngine;
    }

    public Result fetch(GmailConnection connection, String messageId, String sha256) {
        try {
            // One service (one token refresh) for the message and all its attachments.
            Gmail service = gmailEngine.createService(connection);
            GmailMessage message = gmailEngine.fetchMessageDetails(service, messageId);
            List<GmailAttachment> attachments = message.attachments();
            for (GmailAttachment att : attachments) {
                String filename = att.filename() == null ? "" : att.filename().toLowerCase();
                if (!(filename.endsWith(".pdf") || filename.endsWith(".xlsx") || filename.endsWith(".xls"))) {
                    continue;
                }
                byte[] bytes = gmailEngine.fetchAttachmentContentOn(service, messageId, att.attachmentId());
                if (bytes != null && sha256.equalsIgnoreCase(StatementPersistenceService.sha256Hex(bytes))) {
                    return new Result(Kind.FOUND, bytes, att.filename());
                }
            }
            return new Result(Kind.NO_MATCHING_ATTACHMENT, null, null);
        } catch (IOException e) {
            throw GmailEngine.classify(e);
        } catch (MessageGoneException e) {
            return new Result(Kind.MAIL_GONE, null, null);
        }
    }
}
