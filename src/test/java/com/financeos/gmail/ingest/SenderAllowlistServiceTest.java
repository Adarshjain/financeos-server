package com.financeos.gmail.ingest;

import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.exception.ValidationException;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.ingest.event.SenderIngestChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SenderAllowlistServiceTest {

    private GmailSenderRepository senderRepository;
    private UserRepository userRepository;
    private GmailSyncCursorService cursorService;
    private ApplicationEventPublisher eventPublisher;
    private GmailSenderRemovalRepository removalRepository;

    private SenderAllowlistService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        senderRepository = mock(GmailSenderRepository.class);
        userRepository = mock(UserRepository.class);
        cursorService = mock(GmailSyncCursorService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        removalRepository = mock(GmailSenderRemovalRepository.class);

        service = new SenderAllowlistService(
                senderRepository,
                userRepository,
                cursorService,
                eventPublisher,
                removalRepository,
                BankSenderDomains.of(Map.of("hdfcbank.net", "hdfc", "hdfcbank.com", "hdfc", "icicibank.com", "icici"))
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

    private GmailSender sender(String address, String bankId) {
        GmailSender s = new GmailSender();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setSenderAddress(address);
        s.setBankId(bankId);
        return s;
    }

    private GmailSenderRemoval removal(String address) {
        GmailSenderRemoval r = new GmailSenderRemoval();
        r.setUser(user);
        r.setSenderAddress(address);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<GmailSender> savedBatch() {
        org.mockito.ArgumentCaptor<List<GmailSender>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(senderRepository).saveAll(cap.capture());
        return cap.getValue();
    }

    @Test
    void addBankSenders_newBank_addsEveryDomainTaggedWithTheBank() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of());
        when(removalRepository.findByUserId(userId)).thenReturn(List.of());
        when(senderRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<GmailSender> added = service.addBankSenders(userId, "hdfc");

        assertThat(added).extracting(GmailSender::getSenderAddress).containsExactlyInAnyOrder("hdfcbank.net", "hdfcbank.com");
        assertThat(added).allSatisfy(s -> {
            assertThat(s.getBankId()).isEqualTo("hdfc");
            assertThat(s.getName()).isEqualTo("HDFC Bank");
            assertThat(s.getEnabled()).isTrue();
        });
        verify(eventPublisher, times(1)).publishEvent(eq(new SenderIngestChangedEvent(userId)));
        verify(senderRepository, times(1)).findByUserId(userId);
        verify(removalRepository, times(1)).findByUserId(userId);
    }

    @Test
    void addBankSenders_secondAccountAtSameBank_addsNothing() {
        when(senderRepository.findByUserId(userId)).thenReturn(List.of(
                sender("hdfcbank.net", "hdfc"), sender("hdfcbank.com", "hdfc")));
        when(removalRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.addBankSenders(userId, "hdfc")).isEmpty();

        verify(senderRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addBankSenders_skipsTombstonedDomain() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of());
        when(removalRepository.findByUserId(userId)).thenReturn(List.of(removal("hdfcbank.net")));
        when(senderRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addBankSenders(userId, "hdfc");

        assertThat(savedBatch()).extracting(GmailSender::getSenderAddress).containsExactly("hdfcbank.com");
    }

    @Test
    void addBankSenders_existingTypedAddressIsNotDuplicated_anyCase() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of(sender("HDFCBank.NET", null)));
        when(removalRepository.findByUserId(userId)).thenReturn(List.of());
        when(senderRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addBankSenders(userId, "hdfc");

        assertThat(savedBatch()).extracting(GmailSender::getSenderAddress).containsExactly("hdfcbank.com");
    }

    @Test
    void addBankSenders_bankWithoutDomains_doesNothing() {
        assertThat(service.addBankSenders(userId, "sbm")).isEmpty();
        verifyNoInteractions(senderRepository, removalRepository);
    }

    @Test
    void deleteSender_withBank_recordsTombstone() {
        GmailSender s = sender("hdfcbank.net", "hdfc");
        when(senderRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(removalRepository.existsByUserIdAndSenderAddressIgnoreCase(userId, "hdfcbank.net")).thenReturn(false);

        service.deleteSender(userId, s.getId());

        org.mockito.ArgumentCaptor<GmailSenderRemoval> cap = org.mockito.ArgumentCaptor.forClass(GmailSenderRemoval.class);
        verify(removalRepository).save(cap.capture());
        assertThat(cap.getValue().getSenderAddress()).isEqualTo("hdfcbank.net");
        assertThat(cap.getValue().getUser()).isSameAs(user);
        verify(senderRepository).delete(s);
    }

    @Test
    void deleteSender_withoutBank_recordsNothing() {
        GmailSender s = sender("alerts@example.com", null);
        when(senderRepository.findById(s.getId())).thenReturn(Optional.of(s));

        service.deleteSender(userId, s.getId());

        verify(removalRepository, never()).save(any());
        verify(senderRepository).delete(s);
    }

    @Test
    void createSender_manualReAddClearsTombstone_andTagsBank() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GmailSender created = service.createSender(userId, new GmailSenderRequest(null, " HDFCBank.net ", true));

        assertThat(created.getSenderAddress()).isEqualTo("hdfcbank.net");
        assertThat(created.getBankId()).isEqualTo("hdfc");
        verify(removalRepository).deleteByUserIdAndSenderAddressIgnoreCase(userId, "hdfcbank.net");
    }

    @Test
    void createSender_acceptsEmailAndDomain_rejectsTheRest() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.createSender(userId, new GmailSenderRequest(null, "Alerts@Bank.com", true)).getSenderAddress())
                .isEqualTo("alerts@bank.com");
        assertThat(service.createSender(userId, new GmailSenderRequest(null, "bank.co.in", true)).getSenderAddress())
                .isEqualTo("bank.co.in");
        assertThat(service.createSender(userId, new GmailSenderRequest(null, "alerts@bank.com", true)).getBankId()).isNull();

        for (String bad : new String[] {"bank", "a b@c.com", "@bank.com", "alerts@bank", "bank.c", "http://bank.com", "a@b@c.com"}) {
            assertThatThrownBy(() -> service.createSender(userId, new GmailSenderRequest(null, bad, true)))
                    .as(bad).isInstanceOf(ValidationException.class).hasMessage("Enter an email address or a domain");
        }
    }

    @Test
    void updateSender_rejectsInvalidAddress() {
        GmailSender s = sender("alerts@bank.com", null);
        when(senderRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.updateSender(userId, s.getId(), new GmailSenderRequest(null, "nope", true)))
                .isInstanceOf(ValidationException.class).hasMessage("Enter an email address or a domain");
    }

    @Test
    void bankDomains_listsEveryCatalogBank() {
        var all = service.bankDomains();

        assertThat(all).hasSize(com.financeos.domain.account.BankCatalog.BANKS.size());
        assertThat(all).filteredOn(b -> b.bankId().equals("hdfc")).singleElement()
                .satisfies(b -> assertThat(b.domains()).containsExactlyInAnyOrder("hdfcbank.net", "hdfcbank.com"));
        assertThat(all).filteredOn(b -> b.bankId().equals("sbm")).singleElement()
                .satisfies(b -> assertThat(b.domains()).isEmpty());
    }
}
