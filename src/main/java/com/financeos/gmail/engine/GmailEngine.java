package com.financeos.gmail.engine;

import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.internal.*;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gmail Engine - Pure fetch engine.
 * NO business logic, NO parsing, NO transaction creation.
 * Returns raw, uninterpreted data only.
 */
@Component
public class GmailEngine {

    private final GmailApiClient gmailApiClient;

    public GmailEngine(GmailApiClient gmailApiClient) {
        this.gmailApiClient = gmailApiClient;
    }

    public Gmail createService(GmailConnection connection) throws IOException {
        String refreshToken = connection.getEncryptedRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new GmailEngineException(GmailError.INVALID_STATE, "No refresh token available");
        }
        return gmailApiClient.createGmailService(refreshToken);
    }

    /**
     * Uncapped message ID listing by query.
     */
    public List<String> listMessageIds(GmailConnection connection, String query) {
        try {
            Gmail service = createService(connection);
            List<String> messageIds = new ArrayList<>();
            String pageToken = null;
            do {
                ListMessagesResponse listResponse = gmailApiClient.listMessages(
                        service,
                        query,
                        pageToken,
                        100L
                );
                List<Message> messageList = listResponse.getMessages();
                if (messageList != null) {
                    for (Message msg : messageList) {
                        if (msg.getId() != null) {
                            messageIds.add(msg.getId());
                        }
                    }
                }
                pageToken = listResponse.getNextPageToken();
            } while (pageToken != null);

            return messageIds;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                throw new GmailEngineException(GmailError.AUTH_ERROR, "Authentication failed", e);
            }
            if (e.getStatusCode() == 429) {
                throw new GmailEngineException(GmailError.RATE_LIMIT, "Rate limit exceeded", e);
            }
            throw new GmailEngineException(GmailError.NETWORK_ERROR, "Google API error: " + e.getMessage(), e);
        } catch (IOException e) {
            throw handleIOException(e);
        }
    }

    /**
     * Fetch full message details including attachments. Map 404 to MessageGoneException.
     */
    public GmailMessage fetchMessageDetails(GmailConnection connection, String messageId) {
        try {
            Gmail service = createService(connection);
            return fetchMessageDetails(service, messageId);
        } catch (IOException e) {
            throw handleIOException(e);
        }
    }

    /**
     * Fetch full message details using an existing Gmail service instance.
     */
    public GmailMessage fetchMessageDetails(Gmail service, String messageId) {
        try {
            Message message;
            try {
                message = gmailApiClient.getMessage(service, messageId);
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 404) {
                    throw new MessageGoneException(messageId);
                }
                throw e;
            }

            if (message == null) {
                throw new MessageGoneException(messageId);
            }

            String from = extractHeader(message, "From");
            String subject = extractHeader(message, "Subject");
            Long internalDate = message.getInternalDate();
            Instant date = internalDate != null ? Instant.ofEpochMilli(internalDate) : Instant.now();

            String snippet = message.getSnippet();

            StringBuilder textBuilder = new StringBuilder();
            StringBuilder htmlBuilder = new StringBuilder();
            MessagePart payload = message.getPayload();
            if (payload != null) {
                extractBodyPartsRecursive(payload, textBuilder, htmlBuilder);
            }

            List<GmailAttachment> attachments = extractAttachments(message);

            return new GmailMessage(
                    messageId,
                    date,
                    from != null ? from : "",
                    subject != null ? subject : "",
                    snippet != null ? snippet : "",
                    textBuilder.toString(),
                    htmlBuilder.toString(),
                    attachments != null ? attachments : List.of()
            );

        } catch (MessageGoneException e) {
            throw e;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                throw new MessageGoneException(messageId);
            }
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                throw new GmailEngineException(GmailError.AUTH_ERROR, "Authentication failed", e);
            }
            if (e.getStatusCode() == 429) {
                throw new GmailEngineException(GmailError.RATE_LIMIT, "Rate limit exceeded", e);
            }
            throw new GmailEngineException(GmailError.NETWORK_ERROR, "Google API error: " + e.getMessage(), e);
        } catch (IOException e) {
            throw handleIOException(e);
        }
    }

    /**
     * Fetch attachment content lazily.
     */
    public byte[] fetchAttachmentContent(GmailConnection connection, String messageId, String attachmentId) {
        try {
            Gmail service = createService(connection);
            return gmailApiClient.getAttachment(service, messageId, attachmentId);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                throw new MessageGoneException(messageId);
            }
            throw handleIOException(e);
        } catch (IOException e) {
            throw handleIOException(e);
        }
    }

    private GmailEngineException handleIOException(IOException e) {
        String errorMsg = e.getMessage();
        if (errorMsg != null) {
            if (errorMsg.contains("401") || errorMsg.contains("403")) {
                return new GmailEngineException(GmailError.AUTH_ERROR, "Authentication failed", e);
            }
            if (errorMsg.contains("429") || errorMsg.contains("rate limit")) {
                return new GmailEngineException(GmailError.RATE_LIMIT, "Rate limit exceeded", e);
            }
        }
        return new GmailEngineException(GmailError.NETWORK_ERROR, "Network error during fetch", e);
    }

    private String decodeBase64Url(String base64UrlStr) {
        if (base64UrlStr == null || base64UrlStr.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(base64UrlStr), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private void extractBodyPartsRecursive(MessagePart part, StringBuilder textBuilder, StringBuilder htmlBuilder) {
        String mimeType = part.getMimeType();
        MessagePartBody body = part.getBody();

        if (body != null && body.getData() != null && !body.getData().isEmpty() && (part.getFilename() == null || part.getFilename().isEmpty())) {
            String decodedData = decodeBase64Url(body.getData());
            if ("text/plain".equalsIgnoreCase(mimeType)) {
                textBuilder.append(decodedData);
            } else if ("text/html".equalsIgnoreCase(mimeType)) {
                htmlBuilder.append(decodedData);
            }
        }

        List<MessagePart> parts = part.getParts();
        if (parts != null) {
            for (MessagePart subPart : parts) {
                extractBodyPartsRecursive(subPart, textBuilder, htmlBuilder);
            }
        }
    }

    private List<GmailAttachment> extractAttachments(Message message) {
        List<GmailAttachment> attachments = new ArrayList<>();
        MessagePart payload = message.getPayload();
        if (payload == null) {
            return attachments;
        }

        extractAttachmentsRecursive(payload, attachments);
        return attachments;
    }

    private void extractAttachmentsRecursive(MessagePart part, List<GmailAttachment> attachments) {
        if (part.getFilename() != null && !part.getFilename().isEmpty() && part.getBody() != null && part.getBody().getAttachmentId() != null) {
            attachments.add(new GmailAttachment(
                    part.getBody().getAttachmentId(),
                    part.getFilename(),
                    part.getMimeType(),
                    null // Fetched lazily
            ));
        }

        List<MessagePart> parts = part.getParts();
        if (parts != null) {
            for (MessagePart subPart : parts) {
                extractAttachmentsRecursive(subPart, attachments);
            }
        }
    }

    private String extractHeader(Message message, String name) {
        List<MessagePartHeader> headers = message.getPayload() != null ? message.getPayload().getHeaders() : null;
        if (headers == null) {
            return null;
        }
        return headers.stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse(null);
    }
}
