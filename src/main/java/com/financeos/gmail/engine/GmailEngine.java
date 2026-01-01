package com.financeos.gmail.engine;

import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.history.SyncStateService;
import com.financeos.gmail.internal.*;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gmail Engine - Pure fetch engine.
 * NO business logic, NO parsing, NO transaction creation.
 * Returns raw, uninterpreted data only.
 */
@Component
public class GmailEngine {

    private final GmailApiClient gmailApiClient;
    private final SyncStateService syncStateService;

    public GmailEngine(GmailApiClient gmailApiClient, SyncStateService syncStateService) {
        this.gmailApiClient = gmailApiClient;
        this.syncStateService = syncStateService;
    }

    /**
     * Public contract: Fetch emails.
     * 
     * @param connection Gmail connection with encrypted refresh token
     * @param request Fetch request with mode, time range, limits
     * @return Raw messages and attachments, plus next sync state
     * @throws GmailEngineException on fetch errors
     */
    public GmailFetchResult fetch(GmailConnection connection, GmailFetchRequest request) {
        try {
            // Decrypt and create Gmail service
            String refreshToken = connection.getEncryptedRefreshToken();
            if (refreshToken == null || refreshToken.isEmpty()) {
                throw new GmailEngineException(GmailError.INVALID_STATE, "No refresh token available");
            }

            Gmail service = gmailApiClient.createGmailService(refreshToken);

            // Get current sync state
            GmailSyncState currentState = syncStateService.getSyncState(connection.getId());

            // Fetch messages
            List<GmailMessage> messages = fetchMessages(service, request, currentState);

            // Update sync state
            String newHistoryId = extractHistoryId(service, currentState);
            Instant lastSyncedAt = Instant.now();
            syncStateService.saveSyncState(connection, newHistoryId, lastSyncedAt);

            GmailSyncState nextState = new GmailSyncState(newHistoryId, lastSyncedAt);

            return new GmailFetchResult(messages, nextState);

        } catch (IOException e) {
            // Check for specific error types
            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                if (errorMsg.contains("401") || errorMsg.contains("403")) {
                    throw new GmailEngineException(GmailError.AUTH_ERROR, "Authentication failed", e);
                }
                if (errorMsg.contains("429") || errorMsg.contains("rate limit")) {
                    throw new GmailEngineException(GmailError.RATE_LIMIT, "Rate limit exceeded", e);
                }
            }
            throw new GmailEngineException(GmailError.NETWORK_ERROR, "Network error during fetch", e);
        }
    }

    /**
     * Fetch messages based on request mode.
     */
    private List<GmailMessage> fetchMessages(Gmail service, GmailFetchRequest request, GmailSyncState currentState) throws IOException {
        List<GmailMessage> messages;

        if (currentState != null && request.mode() == FetchMode.PERIODIC) {
            // Incremental sync using historyId
            messages = fetchIncremental(service, currentState.historyId(), request.maxMessages());
        } else {
            // Full sync or manual fetch
            messages = fetchFull(service, request.fromTime(), request.maxMessages());
        }

        return messages;
    }

    /**
     * Fetch messages incrementally using history.
     */
    private List<GmailMessage> fetchIncremental(Gmail service, String startHistoryId, Integer maxMessages) throws IOException {
        List<GmailMessage> messages = new ArrayList<>();
        String pageToken = null;
        int fetched = 0;

        BigInteger historyIdBigInt = new BigInteger(startHistoryId);
        long maxResultsLong = maxMessages != null ? Math.min(maxMessages - fetched, 100L) : 100L;

        do {
            ListHistoryResponse historyResponse = gmailApiClient.getHistory(
                    service,
                    historyIdBigInt,
                    pageToken,
                    maxResultsLong
            );

            List<History> historyList = historyResponse.getHistory();
            if (historyList == null) {
                break;
            }

            for (History history : historyList) {
                List<HistoryMessageAdded> addedMessages = history.getMessagesAdded();
                if (addedMessages != null) {
                    for (HistoryMessageAdded added : addedMessages) {
                        if (maxMessages != null && fetched >= maxMessages) {
                            break;
                        }
                        Message msg = added.getMessage();
                        if (msg != null && msg.getId() != null) {
                            GmailMessage gmailMessage = fetchMessageDetails(service, msg.getId());
                            if (gmailMessage != null) {
                                messages.add(gmailMessage);
                                fetched++;
                            }
                        }
                    }
                }
            }

            pageToken = historyResponse.getNextPageToken();
            maxResultsLong = maxMessages != null ? Math.min(maxMessages - fetched, 100L) : 100L;
        } while (pageToken != null && (maxMessages == null || fetched < maxMessages));

        return messages;
    }

    /**
     * Fetch messages with time filter.
     */
    private List<GmailMessage> fetchFull(Gmail service, Instant fromTime, Integer maxMessages) throws IOException {
        List<GmailMessage> messages = new ArrayList<>();
        String query = buildQuery(fromTime);
        String pageToken = null;
        int fetched = 0;

        do {
            long maxResultsLong = maxMessages != null ? Math.min(maxMessages - fetched, 100L) : 100L;
            ListMessagesResponse listResponse = gmailApiClient.listMessages(
                    service,
                    query,
                    pageToken,
                    maxResultsLong
            );

            List<Message> messageList = listResponse.getMessages();
            if (messageList == null) {
                break;
            }

            for (Message msg : messageList) {
                if (maxMessages != null && fetched >= maxMessages) {
                    break;
                }
                GmailMessage gmailMessage = fetchMessageDetails(service, msg.getId());
                if (gmailMessage != null) {
                    messages.add(gmailMessage);
                    fetched++;
                }
            }

            pageToken = listResponse.getNextPageToken();
        } while (pageToken != null && (maxMessages == null || fetched < maxMessages));

        return messages;
    }

    /**
     * Fetch full message details including attachments.
     */
    private GmailMessage fetchMessageDetails(Gmail service, String messageId) throws IOException {
        Message message = gmailApiClient.getMessage(service, messageId);
        if (message == null) {
            return null;
        }

        // Extract headers
        String from = extractHeader(message, "From");
        String subject = extractHeader(message, "Subject");
        Long internalDate = message.getInternalDate();
        Instant date = internalDate != null ? Instant.ofEpochMilli(internalDate) : Instant.now();

        // Extract attachments
        List<GmailAttachment> attachments = extractAttachments(service, message);

        return new GmailMessage(
                messageId,
                date,
                from != null ? from : "",
                subject != null ? subject : "",
                attachments != null ? attachments : List.of()
        );
    }

    /**
     * Extract attachments from message.
     */
    private List<GmailAttachment> extractAttachments(Gmail service, Message message) {
        List<GmailAttachment> attachments = new ArrayList<>();
        MessagePart payload = message.getPayload();
        if (payload == null) {
            return attachments;
        }

        extractAttachmentsRecursive(service, message.getId(), payload, attachments);
        return attachments;
    }

    /**
     * Recursively extract attachments from message parts.
     */
    private void extractAttachmentsRecursive(Gmail service, String messageId, MessagePart part, List<GmailAttachment> attachments) {
        if (part.getFilename() != null && !part.getFilename().isEmpty() && part.getBody() != null && part.getBody().getAttachmentId() != null) {
            try {
                byte[] content = gmailApiClient.getAttachment(service, messageId, part.getBody().getAttachmentId());
                attachments.add(new GmailAttachment(
                        part.getBody().getAttachmentId(),
                        part.getFilename(),
                        part.getMimeType(),
                        content
                ));
            } catch (IOException e) {
                // Skip attachment if fetch fails
            }
        }

        List<MessagePart> parts = part.getParts();
        if (parts != null) {
            for (MessagePart subPart : parts) {
                extractAttachmentsRecursive(service, messageId, subPart, attachments);
            }
        }
    }

    /**
     * Extract header value from message.
     */
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

    /**
     * Build Gmail query string.
     */
    private String buildQuery(Instant fromTime) {
        if (fromTime == null) {
            return "";
        }
        // Gmail query: after:YYYY/MM/DD
        return String.format("after:%d/%02d/%02d",
                fromTime.atZone(java.time.ZoneId.of("UTC")).getYear(),
                fromTime.atZone(java.time.ZoneId.of("UTC")).getMonthValue(),
                fromTime.atZone(java.time.ZoneId.of("UTC")).getDayOfMonth());
    }

    /**
     * Extract or generate historyId.
     */
    private String extractHistoryId(Gmail service, GmailSyncState currentState) throws IOException {
        // Get current historyId from profile (always use latest)
        Profile profile = gmailApiClient.getProfile(service);
        BigInteger historyIdBigInt = profile.getHistoryId();
        if (historyIdBigInt == null) {
            // Fallback to current state if profile doesn't have historyId
            if (currentState != null && currentState.historyId() != null) {
                return currentState.historyId();
            }
            throw new GmailEngineException(GmailError.INVALID_STATE, "Unable to determine historyId");
        }
        return historyIdBigInt.toString();
    }
}
