package com.financeos.gmail.view;

import com.financeos.api.gmail.dto.GmailMessageContentResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "View email": reads one mail live from the user's own mailbox with the access the sync already
 * holds. Nothing is stored. Strictly current-user: another user's ledger id is a plain 404.
 */
@Service
public class GmailMessageContentService {

    public static final String CODE_GONE = "GMAIL_MESSAGE_GONE";
    public static final String CODE_RECONNECT = "GMAIL_RECONNECT_REQUIRED";
    public static final String CODE_UNAVAILABLE = "GMAIL_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(GmailMessageContentService.class);

    private final GmailProcessedMessageRepository processedMessages;
    private final GmailEngine engine;
    private final GmailApiClient apiClient;

    public GmailMessageContentService(GmailProcessedMessageRepository processedMessages, GmailEngine engine,
                                      GmailApiClient apiClient) {
        this.processedMessages = processedMessages;
        this.engine = engine;
        this.apiClient = apiClient;
    }

    public GmailMessageContentResponse content(UUID userId, UUID ledgerId) {
        GmailProcessedMessage row = processedMessages.findOwnedWithConnection(ledgerId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Email", ledgerId));
        GmailConnection connection = row.getConnection();
        if (connection.getAuthFailedAt() != null) {
            throw reconnect();
        }
        Message message;
        try {
            Gmail service = engine.createService(connection);
            message = apiClient.getMessage(service, row.getGmailMessageId());
        } catch (GmailEngineException e) {
            throw map(e);
        } catch (IOException e) {
            throw map(GmailEngine.classify(e));
        }
        if (message == null) {
            throw gone();
        }
        return toResponse(message);
    }

    private ApiStatusException map(GmailEngineException e) {
        if (e.getErrorType() == GmailError.AUTH_ERROR || e.getErrorType() == GmailError.INVALID_STATE) {
            return reconnect();
        }
        if (e.getErrorType() == GmailError.NOT_FOUND) {
            return gone();
        }
        log.warn("View email: Gmail failed ({}): {}", e.getErrorType(), e.getMessage());
        return new ApiStatusException(HttpStatus.BAD_GATEWAY, CODE_UNAVAILABLE, "Gmail could not be reached. Try again.");
    }

    private static ApiStatusException reconnect() {
        return new ApiStatusException(HttpStatus.CONFLICT, CODE_RECONNECT, "Reconnect Gmail to view this email.");
    }

    private static ApiStatusException gone() {
        return new ApiStatusException(HttpStatus.GONE, CODE_GONE, "This email is no longer in your mailbox.");
    }

    static GmailMessageContentResponse toResponse(Message message) {
        MimeContent mime = MimeContent.walk(message.getPayload());
        boolean skipHtml = mime.htmlTooLarge() || EmailHtmlSanitizer.tooDeep(mime.html());
        String html = skipHtml ? "" : EmailHtmlSanitizer.sanitize(mime.html());
        boolean hasHtml = !html.isBlank();
        String text = mime.text() == null || mime.text().isBlank() ? null : mime.text();
        // an HTML body we would not parse, with no text to fall back on: say it is too large
        boolean tooLarge = skipHtml && text == null;
        List<GmailMessageContentResponse.GmailMessageContentAttachment> attachments = mime.attachments().stream()
                .map(a -> new GmailMessageContentResponse.GmailMessageContentAttachment(a.filename(),
                        a.mimeType() == null ? "" : a.mimeType(), a.size()))
                .toList();
        return new GmailMessageContentResponse(
                nz(header(message, "Subject")),
                nz(header(message, "From")),
                nz(header(message, "To")),
                message.getInternalDate() != null ? Instant.ofEpochMilli(message.getInternalDate()) : null,
                hasHtml ? html : null,
                hasHtml ? null : text,
                attachments,
                tooLarge);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String header(Message message, String name) {
        List<MessagePartHeader> headers = message.getPayload() != null ? message.getPayload().getHeaders() : null;
        if (headers == null) {
            return null;
        }
        for (MessagePartHeader h : headers) {
            if (name.equalsIgnoreCase(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }
}
