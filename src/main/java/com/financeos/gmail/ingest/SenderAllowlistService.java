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

import java.time.LocalDate;
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

    /** Document handlers (NPS) whose senders every connected user gets; null = none (hand-built tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<GmailDocumentHandler> documentHandlers;

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

    /** {@link #addBankSenders(UUID, String, LocalDate)} without a backfill floor (the senders follow the user's demand floor). */
    public List<GmailSender> addBankSenders(UUID userId, String bankId) {
        return addBankSenders(userId, bankId, null);
    }

    /**
     * Adds each sender domain of {@code bankId} to the user's senders (bare domain, enabled, tagged with the bank),
     * skipping addresses the user already has (any case) and addresses they removed before. One read of the user's
     * senders and removals; one ingest event.
     *
     * <p>{@code fromDate} (the account's ingest-from date) becomes the new senders' backfill floor, so their Gmail
     * backfill reaches back no further than that account, whatever the user-level demand floor is. A bank sender
     * added earlier that has a floor later than {@code fromDate} is lowered to it (two accounts at one bank);
     * senders without a floor are left to follow the demand floor.
     */
    public List<GmailSender> addBankSenders(UUID userId, String bankId, LocalDate fromDate) {
        List<String> ownDomains = bankSenderDomains.domainsFor(bankId);
        // fintech brands whose mail covers this bank's accounts (federal: fi, jupiter; csb: jupiter) are added too
        List<String> domains = new ArrayList<>(ownDomains);
        for (String brand : com.financeos.gmail.accountdetect.BankPartners.brandsFor(bankId)) {
            domains.addAll(bankSenderDomains.domainsFor(brand));
        }
        if (domains.isEmpty()) {
            return List.of();
        }
        Set<String> skip = new HashSet<>();
        List<GmailSender> lowered = new ArrayList<>();
        for (GmailSender s : gmailSenderRepository.findByUserId(userId)) {
            String address = s.getSenderAddress().toLowerCase(Locale.ROOT);
            skip.add(address);
            if (fromDate != null && bankId.equals(s.getBankId()) && domains.contains(address)
                    && s.getBackfillFrom() != null && s.getBackfillFrom().isAfter(fromDate)) {
                s.setBackfillFrom(fromDate);
                lowered.add(s);
            }
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
            sender.setBackfillFrom(fromDate);
            added.add(sender);
        }
        if (!lowered.isEmpty()) {
            gmailSenderRepository.saveAll(lowered);
        }
        if (added.isEmpty()) {
            if (!lowered.isEmpty()) {
                eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
            }
            return List.of();
        }
        List<GmailSender> saved = gmailSenderRepository.saveAll(added);
        eventPublisher.publishEvent(new SenderIngestChangedEvent(userId));
        return saved;
    }

    /**
     * Adds the senders of every document handler (NPS) to the user's senders: exact address, enabled, flagged as a
     * document sender and floored at the handler's own date (their backfill reaches back to it, whatever the user's
     * demand floor). Skips addresses the user already has (any case) or removed. Publishes no ingest event: the connect queues the sync.
     * Runs in its own transaction: the Gmail connect calls it best-effort, and a failure here must roll back only
     * this call, never mark the connect's transaction rollback-only.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<GmailSender> addDocumentSenders(UUID userId) {
        if (documentHandlers == null || documentHandlers.isEmpty()) {
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
        List<GmailSender> added = new ArrayList<>();
        for (GmailDocumentHandler handler : documentHandlers) {
            for (String address : handler.senderAddresses()) {
                String lower = address.toLowerCase(Locale.ROOT);
                if (!skip.add(lower)) {
                    continue;
                }
                if (user == null) {
                    user = userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
                }
                GmailSender sender = new GmailSender();
                sender.setUser(user);
                sender.setName(handler.senderName());
                sender.setSenderAddress(lower);
                sender.setEnabled(true);
                sender.setDocumentSender(true);
                sender.setBackfillFrom(handler.backfillFrom());
                added.add(sender);
            }
        }
        if (added.isEmpty()) {
            return List.of();
        }
        // No SenderIngestChangedEvent from this inner transaction: the connect that calls it queues a sync itself.
        return gmailSenderRepository.saveAll(added);
    }

    @Transactional(readOnly = true)
    public List<GmailSender> getSenders(UUID userId) {
        return gmailSenderRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
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
        String rawAddress = request.senderAddress() == null ? "" : request.senderAddress().trim();
        boolean addressChanged = !rawAddress.equalsIgnoreCase(sender.getSenderAddress());
        // An unchanged address is not re-validated (a stored one may predate today's rules); only a changed one is.
        String newAddress = addressChanged ? normalizeAddress(rawAddress) : sender.getSenderAddress();
        addressChanged = !newAddress.equalsIgnoreCase(sender.getSenderAddress());

        sender.setName(request.name() != null && !request.name().trim().isEmpty() ? request.name().trim() : null);
        if (addressChanged) {
            sender.setSenderAddress(newAddress);
            sender.setBankId(bankSenderDomains.bankIdFor(newAddress));
            sender.setBackfillFrom(null);
            removalRepository.deleteByUserIdAndSenderAddressIgnoreCase(userId, newAddress);
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

        if (sender.getBankId() != null || Boolean.TRUE.equals(sender.getDocumentSender())) {
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
