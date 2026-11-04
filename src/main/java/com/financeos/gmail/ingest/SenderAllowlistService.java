package com.financeos.gmail.ingest;

import com.financeos.api.gmail.dto.BankDomainsResponse;
import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.BankCatalog;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.ingest.event.SenderIngestChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class SenderAllowlistService {

    private final GmailSenderRepository gmailSenderRepository;
    private final UserRepository userRepository;
    private final GmailSyncCursorService syncCursorService;
    private final ApplicationEventPublisher eventPublisher;
    private final GmailSenderRemovalRepository removalRepository;
    private final BankSenderDomains bankSenderDomains;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DOMAIN = Pattern.compile("^([a-z0-9-]+\\.)+[a-z]{2,}$");
    private static final String BAD_ADDRESS = "Enter an email address or a domain";

    public SenderAllowlistService(GmailSenderRepository gmailSenderRepository,
                                  UserRepository userRepository,
                                  GmailSyncCursorService syncCursorService,
                                  ApplicationEventPublisher eventPublisher,
                                  GmailSenderRemovalRepository removalRepository,
                                  BankSenderDomains bankSenderDomains) {
        this.gmailSenderRepository = gmailSenderRepository;
        this.userRepository = userRepository;
        this.syncCursorService = syncCursorService;
        this.eventPublisher = eventPublisher;
        this.removalRepository = removalRepository;
        this.bankSenderDomains = bankSenderDomains;
    }

    /** Every catalog bank with the sender domains that are added for it. */
    @Transactional(readOnly = true)
    public List<BankDomainsResponse> bankDomains() {
        return BankCatalog.BANKS.stream()
                .map(b -> new BankDomainsResponse(b.id(), bankSenderDomains.domainsFor(b.id())))
                .toList();
    }

    /** Trimmed, lower-cased email address or bare domain; anything else is a 400. */
    static String normalizeAddress(String raw) {
        String a = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(a).matches() && !DOMAIN.matcher(a).matches()) {
            throw new ValidationException(BAD_ADDRESS);
        }
        return a;
    }

    /**
     * Adds each sender domain of {@code bankId} to the user's senders (bare domain, enabled, tagged with the bank),
     * skipping addresses the user already has (any case) and addresses they removed before. One read of the user's
     * senders and removals; one ingest event.
     */
    public List<GmailSender> addBankSenders(UUID userId, String bankId) {
        List<String> domains = bankSenderDomains.domainsFor(bankId);
        if (domains.isEmpty()) {
            return List.of();
        }
        Set<String> skip = new HashSet<>();
        for (GmailSender s : gmailSenderRepository.findByUserId(userId)) {
            skip.add(s.getSenderAddress().toLowerCase(Locale.ROOT));
        }
        for (GmailSenderRemoval r : removalRepository.findByUserId(userId)) {
            skip.add(r.getSenderAddress().toLowerCase(Locale.ROOT));
        }
        User user = null;
        String name = BankCatalog.find(bankId).map(BankCatalog.Bank::name).orElse(null);
        List<GmailSender> added = new ArrayList<>();
        for (String domain : domains) {
            if (!skip.add(domain)) {
                continue;
            }
            if (user == null) {
                user = userRepository.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
            }
            GmailSender sender = new GmailSender();
            sender.setUser(user);
            sender.setName(name);
            sender.setSenderAddress(domain);
            sender.setEnabled(true);
            sender.setBankId(bankId);
            added.add(sender);
        }
        if (added.isEmpty()) {
            return List.of();
        }
        List<GmailSender> saved = gmailSenderRepository.saveAll(added);
        eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
        return saved;
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
        String address = normalizeAddress(request.senderAddress());
        sender.setSenderAddress(address);
        sender.setEnabled(request.enabled() != null ? request.enabled() : true);
        sender.setBankId(bankSenderDomains.bankIdFor(address));
        removalRepository.deleteByUserIdAndSenderAddressIgnoreCase(userId, address);

        GmailSender saved = gmailSenderRepository.save(sender);
        eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
        return saved;
    }

    public GmailSender updateSender(UUID userId, UUID senderId, GmailSenderRequest request) {
        GmailSender sender = gmailSenderRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + senderId));

        if (!sender.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to access this sender.");
        }

        boolean oldEnabled = Boolean.TRUE.equals(sender.getEnabled());
        String newAddress = normalizeAddress(request.senderAddress());
        boolean addressChanged = !newAddress.equalsIgnoreCase(sender.getSenderAddress());

        sender.setName(request.name() != null && !request.name().trim().isEmpty() ? request.name().trim() : null);
        sender.setSenderAddress(newAddress);
        if (addressChanged) {
            sender.setBankId(bankSenderDomains.bankIdFor(newAddress));
        }
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
            throw new ValidationException("You do not have permission to access this sender.");
        }

        if (sender.getBankId() != null) {
            String address = sender.getSenderAddress().toLowerCase(Locale.ROOT);
            if (!removalRepository.existsByUserIdAndSenderAddressIgnoreCase(userId, address)) {
                GmailSenderRemoval removal = new GmailSenderRemoval();
                removal.setUser(sender.getUser());
                removal.setSenderAddress(address);
                removalRepository.save(removal);
            }
        }
        gmailSenderRepository.delete(sender);
    }
}
