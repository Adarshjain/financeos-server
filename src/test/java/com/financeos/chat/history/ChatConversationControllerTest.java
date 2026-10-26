package com.financeos.chat.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.history.ChatHistoryDtos.DraftResultRequest;
import com.financeos.chat.history.ChatHistoryDtos.RenameRequest;
import com.financeos.core.security.UserContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ChatConversationControllerTest {

    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatConversationController controller = new ChatConversationController(history);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void everyCallIsScopedToTheAuthenticatedUser() {
        UUID id = UUID.randomUUID();
        UUID msg = UUID.randomUUID();
        ObjectNode draft = new ObjectMapper().createObjectNode().put("action", "saved");
        when(history.list(userId)).thenReturn(List.of());

        assertTrue(controller.list().isEmpty());
        controller.get(id);
        controller.rename(id, new RenameRequest("Title"));
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(id).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.setDraftResult(id, msg, new DraftResultRequest(draft)).getStatusCode());

        verify(history).get(userId, id);
        verify(history).rename(userId, id, "Title");
        verify(history).delete(userId, id);
        verify(history).setDraftResult(userId, id, msg, draft);
    }

    @Test
    void unauthenticatedIs401() {
        UserContext.clear();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, controller::list);
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
