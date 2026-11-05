package com.financeos.gmail.ingest;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.classify.BankSenderDomains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The senders of every document handler (NPS) reach each connected user, floored at the handler's own date. */
class SenderAllowlistDocumentSendersTest {

    private GmailSenderRepository senderRepository;
    private GmailSenderRemovalRepository removalRepository;
    private ApplicationEventPublisher events;
    private SenderAllowlistService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        senderRepository = mock(GmailSenderRepository.class);
        removalRepository = mock(GmailSenderRemovalRepository.class);
        events = mock(ApplicationEventPublisher.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new SenderAllowlistService(senderRepository, userRepository, mock(GmailSyncCursorService.class),
                events, removalRepository, BankSenderDomains.of(Map.of()));
        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        GmailDocumentHandler handler = mock(GmailDocumentHandler.class);
        when(handler.senderAddresses()).thenReturn(List.of("nps-alerts@proteantech.in", "CRA@nsdl.co.in"));
        when(handler.senderName()).thenReturn("NPS (Protean)");
        when(handler.backfillFrom()).thenReturn(LocalDate.of(2013, 1, 1));
        ReflectionTestUtils.setField(service, "documentHandlers", List.of(handler));
    }

    @Test
    void addsEachSenderEnabledFlaggedAndFlooredOnceWithoutAnIngestEvent() {
        List<GmailSender> added = service.addDocumentSenders(userId);

        assertThat(added).extracting(GmailSender::getSenderAddress)
                .containsExactly("nps-alerts@proteantech.in", "cra@nsdl.co.in");
        assertThat(added).allSatisfy(s -> {
            assertThat(s.getEnabled()).isTrue();
            assertThat(s.getDocumentSender()).isTrue();
            assertThat(s.getBackfillFrom()).isEqualTo(LocalDate.of(2013, 1, 1));
            assertThat(s.getBankId()).isNull();
        });
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void skipsAddressesTheUserAlreadyHasOrRemoved() {
        GmailSender existing = new GmailSender();
        existing.setSenderAddress("NPS-ALERTS@proteantech.in");
        when(senderRepository.findByUserId(userId)).thenReturn(List.of(existing));
        GmailSenderRemoval removed = new GmailSenderRemoval();
        removed.setSenderAddress("cra@nsdl.co.in");
        when(removalRepository.findByUserId(userId)).thenReturn(List.of(removed));

        assertThat(service.addDocumentSenders(userId)).isEmpty();
        verify(senderRepository, never()).saveAll(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void noHandlersMeansNoSenders() {
        ReflectionTestUtils.setField(service, "documentHandlers", null);

        assertThat(service.addDocumentSenders(userId)).isEmpty();
    }
}
