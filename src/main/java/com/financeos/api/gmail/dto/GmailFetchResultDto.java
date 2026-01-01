package com.financeos.api.gmail.dto;

import com.financeos.gmail.internal.GmailFetchResult;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.GmailSyncState;

import java.util.List;

public record GmailFetchResultDto(
        List<GmailMessageDto> messages,
        GmailSyncStateDto nextState
) {
    public static GmailFetchResultDto from(GmailFetchResult result) {
        return new GmailFetchResultDto(
                result.messages().stream()
                        .map(GmailMessageDto::from)
                        .toList(),
                GmailSyncStateDto.from(result.nextState())
        );
    }

    public record GmailMessageDto(
            String messageId,
            String internalDate,
            String from,
            String subject,
            List<GmailAttachmentDto> attachments
    ) {
        public static GmailMessageDto from(GmailMessage message) {
            return new GmailMessageDto(
                    message.messageId(),
                    message.internalDate().toString(),
                    message.from(),
                    message.subject(),
                    message.attachments().stream()
                            .map(GmailAttachmentDto::from)
                            .toList()
            );
        }
    }

    public record GmailAttachmentDto(
            String attachmentId,
            String filename,
            String mimeType,
            Integer contentLength  // bytes length, not actual content
    ) {
        public static GmailAttachmentDto from(com.financeos.gmail.internal.GmailAttachment attachment) {
            return new GmailAttachmentDto(
                    attachment.attachmentId(),
                    attachment.filename(),
                    attachment.mimeType(),
                    attachment.content() != null ? attachment.content().length : 0
            );
        }
    }

    public record GmailSyncStateDto(
            String historyId,
            String lastSyncedAt
    ) {
        public static GmailSyncStateDto from(GmailSyncState state) {
            return new GmailSyncStateDto(
                    state.historyId(),
                    state.lastSyncedAt().toString()
            );
        }
    }
}

