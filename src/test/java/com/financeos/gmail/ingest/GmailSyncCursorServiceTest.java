package com.financeos.gmail.ingest;

import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GmailSyncCursorServiceTest {

    private GmailSyncCursorRepository cursorRepository;
    private GmailSenderRepository senderRepository;
    private GmailIngestProperties ingestProperties;
    private GmailSyncCursorService cursorService;

    private GmailConnection connection;
    private GmailSender sender;

    @BeforeEach
    void setUp() {
        cursorRepository = mock(GmailSyncCursorRepository.class);
        senderRepository = mock(GmailSenderRepository.class);
        ingestProperties = mock(GmailIngestProperties.class);
        cursorService = new GmailSyncCursorService(cursorRepository, senderRepository, ingestProperties);

        when(ingestProperties.getFirstBackfillDays()).thenReturn(30);

        User user = new User();
        user.setId(UUID.randomUUID());

        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);

        sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setSenderAddress("alerts@bank.com");
    }

    @Test
    void testGetOrSeedCursorsSeedsMissingCursor() {
        when(senderRepository.findByUserIdAndEnabledTrue(connection.getUser().getId())).thenReturn(List.of(sender));
        when(cursorRepository.findByConnectionId(connection.getId())).thenReturn(List.of());
        when(cursorRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<GmailSyncCursor> cursors = cursorService.getOrSeedCursors(connection);

        assertThat(cursors).hasSize(1);
        verify(cursorRepository).save(any(GmailSyncCursor.class));
    }

    @Test
    void testResetCursorForSender() {
        UUID senderId = UUID.randomUUID();
        cursorService.resetCursorForSender(senderId);
        verify(cursorRepository).deleteBySenderId(senderId);
    }
}
