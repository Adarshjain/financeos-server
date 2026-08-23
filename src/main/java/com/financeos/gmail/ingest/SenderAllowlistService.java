package com.financeos.gmail.ingest;

import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.SenderIngestChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SenderAllowlistService {

    private final GmailSenderRepository gmailSenderRepository;
    private final UserRepository userRepository;
    private final GmailSyncCursorService syncCursorService;
    private final ApplicationEventPublisher eventPublisher;

    public SenderAllowlistService(GmailSenderRepository gmailSenderRepository,
                                  UserRepository userRepository,
                                  GmailSyncCursorService syncCursorService,
                                  ApplicationEventPublisher eventPublisher) {
        this.gmailSenderRepository = gmailSenderRepository;
        this.userRepository = userRepository;
        this.syncCursorService = syncCursorService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<GmailSender> getSenders(UUID userId) {
        return gmailSenderRepository.findByUserId(userId);
    }

    public GmailSender createSender(UUID userId, GmailSenderRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        GmailSender sender = new GmailSender();
        sender.setUser(user);
        sender.setName(request.name() != null && !request.name().trim().isEmpty() ? request.name().trim() : null);
        sender.setSenderAddress(request.senderAddress().trim().toLowerCase());
        sender.setEnabled(request.enabled() != null ? request.enabled() : true);

        GmailSender saved = gmailSenderRepository.save(sender);
        eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
        return saved;
    }

    public GmailSender updateSender(UUID userId, UUID senderId, GmailSenderRequest request) {
        GmailSender sender = gmailSenderRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + senderId));

        if (!sender.getUser().getId().equals(userId)) {
            throw new SecurityException("Unauthorized access to Gmail sender");
        }

        boolean oldEnabled = Boolean.TRUE.equals(sender.getEnabled());
        String newAddress = request.senderAddress().trim().toLowerCase();
        boolean addressChanged = !newAddress.equalsIgnoreCase(sender.getSenderAddress());

        sender.setName(request.name() != null && !request.name().trim().isEmpty() ? request.name().trim() : null);
        sender.setSenderAddress(newAddress);
        if (request.enabled() != null) {
            sender.setEnabled(request.enabled());
        }
        boolean newEnabled = Boolean.TRUE.equals(sender.getEnabled());
        boolean justEnabled = !oldEnabled && newEnabled;

        if (addressChanged) {
            syncCursorService.resetCursorForSender(senderId);
        }

        GmailSender saved = gmailSenderRepository.save(sender);

        if (justEnabled || addressChanged) {
            eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
        }

        return saved;
    }

    public void deleteSender(UUID userId, UUID senderId) {
        GmailSender sender = gmailSenderRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + senderId));

        if (!sender.getUser().getId().equals(userId)) {
            throw new SecurityException("Unauthorized access to Gmail sender");
        }

        gmailSenderRepository.delete(sender);
    }
}
