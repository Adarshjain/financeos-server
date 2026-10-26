package com.financeos.chat.history;

import com.financeos.chat.history.ChatHistoryDtos.ConversationDetail;
import com.financeos.chat.history.ChatHistoryDtos.ConversationSummary;
import com.financeos.chat.history.ChatHistoryDtos.DraftResultRequest;
import com.financeos.chat.history.ChatHistoryDtos.RenameRequest;
import com.financeos.core.security.UserContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/chat/conversations")
public class ChatConversationController {

    private final ChatHistoryService history;

    public ChatConversationController(ChatHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public List<ConversationSummary> list() {
        return history.list(userId());
    }

    @GetMapping("/{id}")
    public ConversationDetail get(@PathVariable UUID id) {
        return history.get(userId(), id);
    }

    @PatchMapping("/{id}")
    public ConversationSummary rename(@PathVariable UUID id, @RequestBody RenameRequest request) {
        return history.rename(userId(), id, request.title());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        history.delete(userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/messages/{messageId}")
    public ResponseEntity<Void> setDraftResult(@PathVariable UUID id, @PathVariable UUID messageId,
                                               @RequestBody DraftResultRequest request) {
        history.setDraftResult(userId(), id, messageId, request.draftResult());
        return ResponseEntity.noContent().build();
    }

    private static UUID userId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }
}
