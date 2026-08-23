package com.financeos.gmail.ingest;

import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.SenderIngestChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SenderAllowlistServiceTest {

    private GmailSenderRepository senderRepository;
    private UserRepository userRepository;
    private GmailSyncCursorService cursorService;
    private ApplicationEventPublisher eventPublisher;

    private SenderAllowlistService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        senderRepository = mock(GmailSenderRepository.class);
        userRepository = mock(UserRepository.class);
        cursorService = mock(GmailSyncCursorService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new SenderAllowlistService(
                senderRepository,
                userRepository,
                cursorService,
                eventPublisher
        );

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
    }

    @Test
    void testCreateSender_PublishesSenderIngestChangedEvent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GmailSenderRequest req = new GmailSenderRequest("Bank", "alerts@bank.com", true);
        GmailSender created = service.createSender(userId, req);

        assertThat(created.getSenderAddress()).isEqualTo("alerts@bank.com");
        verify(eventPublisher).publishEvent(eq(new SenderIngestChangedEvent(userId)));
    }

    @Test
    void testUpdateSender_EnablingPublishesEvent() {
        UUID senderId = UUID.randomUUID();
        GmailSender sender = new GmailSender();
        sender.setId(senderId);
        sender.setUser(user);
        sender.setSenderAddress("alerts@bank.com");
        sender.setEnabled(false); // previously disabled

        when(senderRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(senderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GmailSenderRequest req = new GmailSenderRequest("Bank", "alerts@bank.com", true); // newly enabled
        service.updateSender(userId, senderId, req);

        verify(eventPublisher).publishEvent(eq(new SenderIngestChangedEvent(userId)));
    }

    @Test
    void testUpdateSender_DisablingDoesNotPublishEvent() {
        UUID senderId = UUID.randomUUID();
        GmailSender sender = new GmailSender();
        sender.setId(senderId);
        sender.setUser(user);
        sender.setSenderAddress("alerts@bank.com");
        sender.setEnabled(true); // previously enabled

        when(senderRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(senderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GmailSenderRequest req = new GmailSenderRequest("Bank", "alerts@bank.com", false); // disabled
        service.updateSender(userId, senderId, req);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
