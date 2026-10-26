package com.financeos.chat.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatHistoryDtos {

    private ChatHistoryDtos() {}

    public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt, long questionCount) {}

    /** USER messages carry {@code text}; ASSISTANT messages carry {@code answer} (the stored payload object). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageView(UUID id, String role, Instant createdAt, String text, JsonNode answer,
                              @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode draftResult) {}

    public record ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt, List<MessageView> messages) {}

    public record RenameRequest(String title) {}

    public record DraftResultRequest(JsonNode draftResult) {}
}
