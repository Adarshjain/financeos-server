package com.financeos.chat.api;

import com.financeos.chat.orchestrator.ChatMessage;
import java.util.List;
import java.util.UUID;

/**
 * {@code conversationId}, {@code newConversation} and {@code retry} are optional; when neither of the first two is
 * set the request is the stateless transcript call the old chat page makes.
 */
public record ChatStreamRequest(List<ChatMessage> messages, UUID conversationId, Boolean newConversation, Boolean retry) {

    public ChatStreamRequest(List<ChatMessage> messages) {
        this(messages, null, null, null);
    }

    public boolean isNewConversation() {
        return Boolean.TRUE.equals(newConversation);
    }

    public boolean isRetry() {
        return Boolean.TRUE.equals(retry);
    }

    public boolean isPersisted() {
        return conversationId != null || isNewConversation();
    }
}
