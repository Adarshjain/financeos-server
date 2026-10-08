package com.financeos.domain.notification;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.push.WebPushCrypto;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.notification.push.PushSubscription;
import com.financeos.domain.notification.push.WebPushSender;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preferences, device subscriptions and per-card / per-loan mutes, plus the one delivery primitive every
 * producer uses: {@link #deliver} fans a message out to the user's devices and prunes the ones
 * the browser has dropped.
 */
@Service
public class NotificationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);
    static final int MAX_SUBSCRIPTIONS = 10;
    static final int MAX_OFFSETS = 8;
    static final int MAX_OFFSET_DAYS = 60;
    private static final int MAX_USER_AGENT = 200;

    public record View(boolean pushEnabled, int sendHour, List<Integer> reminderOffsets,
                       Map<NotificationKind, Boolean> kinds, List<PushSubscription> subscriptions,
                       List<UUID> mutedAccountIds, List<UUID> mutedLoanIds, boolean pushConfigured) {
    }

    private final UserNotificationSettingsRepository repository;
    private final AccountRepository accountRepository;
    private final LoanRepository loanRepository;
    private final WebPushSender sender;

    public NotificationSettingsService(UserNotificationSettingsRepository repository,
                                       AccountRepository accountRepository,
                                       LoanRepository loanRepository,
                                       WebPushSender sender) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.loanRepository = loanRepository;
        this.sender = sender;
    }

    @Transactional(readOnly = true)
    public View view(UUID userId) {
        UserNotificationSettings settings = repository.findById(userId).orElseGet(() -> new UserNotificationSettings(userId));
        return toView(settings);
    }

    @Transactional
    public View update(UUID userId, Boolean pushEnabled, Integer sendHour, List<Integer> offsets,
                       Map<String, Boolean> kinds) {
        UserNotificationSettings settings = getOrCreate(userId);
        if (pushEnabled != null) {
            settings.setPushEnabled(pushEnabled);
        }
        if (sendHour != null) {
            if (sendHour < 0 || sendHour > 23) {
                throw new ValidationException("Send hour must be between 0 and 23");
            }
            settings.setSendHour(sendHour);
        }
        if (offsets != null) {
            if (offsets.isEmpty()) {
                throw new ValidationException("At least one reminder offset is required");
            }
            if (offsets.size() > MAX_OFFSETS) {
                throw new ValidationException("At most " + MAX_OFFSETS + " reminder offsets are allowed");
            }
            for (Integer offset : offsets) {
                if (offset == null || offset < 0 || offset > MAX_OFFSET_DAYS) {
                    throw new ValidationException("Reminder offsets must be between 0 and " + MAX_OFFSET_DAYS + " days before the due date");
                }
            }
            settings.setReminderOffsets(NotificationSettingsCodec.writeOffsets(offsets));
        }
        if (kinds != null) {
            Map<NotificationKind, Boolean> merged = NotificationSettingsCodec.parseKinds(settings.getKindsJson());
            for (Map.Entry<String, Boolean> entry : kinds.entrySet()) {
                NotificationKind kind;
                try {
                    kind = NotificationKind.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Unknown notification kind: " + entry.getKey());
                }
                if (entry.getValue() != null) {
                    merged.put(kind, entry.getValue());
                }
            }
            settings.setKindsJson(NotificationSettingsCodec.writeKinds(merged));
        }
        return toView(repository.save(settings));
    }

    /** Registers (or refreshes) one browser's subscription; the newest {@value #MAX_SUBSCRIPTIONS} are kept. */
    @Transactional
    public View addSubscription(UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        validateEndpoint(endpoint);
        validateKey(p256dh, 65, "p256dh");
        validateKey(auth, 16, "auth");
        UserNotificationSettings settings = getOrCreate(userId);
        List<PushSubscription> subscriptions = NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions());
        subscriptions.removeIf(s -> endpoint.equals(s.endpoint()));
        String agent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), MAX_USER_AGENT));
        subscriptions.add(new PushSubscription(endpoint, p256dh, auth, agent, Instant.now()));
        subscriptions.sort(Comparator.comparing(PushSubscription::addedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        while (subscriptions.size() > MAX_SUBSCRIPTIONS) {
            subscriptions.remove(0);
        }
        settings.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(subscriptions));
        return toView(repository.save(settings));
    }

    @Transactional
    public View removeSubscription(UUID userId, String endpoint) {
        UserNotificationSettings settings = repository.findById(userId).orElse(null);
        if (settings == null) {
            return toView(new UserNotificationSettings(userId));
        }
        removeSubscriptions(settings, endpoint == null ? List.of() : List.of(endpoint));
        return toView(repository.save(settings));
    }

    @Transactional
    public View setAccountMuted(UUID userId, UUID accountId, boolean muted) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        account.setNotificationsMuted(muted);
        accountRepository.save(account);
        return view(userId);
    }

    /** Per-loan EMI mute; same ownership contract as accounts (foreign loan answers 400). */
    @Transactional
    public View setLoanMuted(UUID userId, UUID loanId, boolean muted) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
        if (loan.getUser() == null || !loan.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to access this loan.");
        }
        loan.setNotificationsMuted(muted);
        loanRepository.save(loan);
        return view(userId);
    }

    /** Sends a hello to every device so the user can see push actually works. Returns how many accepted it. */
    @Transactional
    public int sendTest(UUID userId) {
        if (!sender.isConfigured()) {
            throw new ValidationException("Push notifications are not configured on this server.");
        }
        UserNotificationSettings settings = repository.findById(userId).orElse(null);
        if (settings == null || NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions()).isEmpty()) {
            throw new ValidationException("No device is registered for push notifications yet.");
        }
        return deliver(settings, new PushMessage("FinanceOS notifications are on",
                "You'll get a nudge here when a bill, EMI or mailbox needs attention.", "/settings/notifications", "financeos-test"));
    }

    /**
     * Fans {@code message} out to every registered device. Dropped subscriptions (404/410) are
     * pruned on the spot; other failures are logged and skipped. Returns the number of devices
     * that accepted the message.
     */
    @Transactional
    public int deliver(UserNotificationSettings settings, PushMessage message) {
        List<PushSubscription> subscriptions = NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions());
        if (subscriptions.isEmpty() || !sender.isConfigured()) {
            return 0;
        }
        int sent = 0;
        List<String> gone = new ArrayList<>();
        for (PushSubscription subscription : subscriptions) {
            WebPushSender.SendResult result = sender.send(subscription, message);
            if (result.ok()) {
                sent++;
            } else if (result.gone()) {
                gone.add(subscription.endpoint());
            }
        }
        if (!gone.isEmpty()) {
            removeSubscriptions(settings, gone);
            repository.save(settings);
            log.info("Pruned {} expired push subscription(s) for user {}", gone.size(), settings.getUserId());
        }
        return sent;
    }

    // ---------------------------------------------------------------- helpers

    private UserNotificationSettings getOrCreate(UUID userId) {
        return repository.findById(userId).orElseGet(() -> repository.save(new UserNotificationSettings(userId)));
    }

    private void removeSubscriptions(UserNotificationSettings settings, Collection<String> endpoints) {
        Set<String> drop = new HashSet<>(endpoints);
        List<PushSubscription> subscriptions = NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions());
        subscriptions.removeIf(s -> drop.contains(s.endpoint()));
        settings.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(subscriptions));
    }

    private View toView(UserNotificationSettings settings) {
        List<UUID> muted = accountRepository.findByUserId(settings.getUserId()).stream()
                .filter(a -> Boolean.TRUE.equals(a.getNotificationsMuted()))
                .map(Account::getId)
                .toList();
        List<UUID> mutedLoans = loanRepository.findByUser_IdAndNotificationsMutedTrue(settings.getUserId()).stream()
                .map(Loan::getId)
                .toList();
        Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(settings.getKindsJson()));
        return new View(
                !Boolean.FALSE.equals(settings.getPushEnabled()),
                settings.getSendHour() == null ? UserNotificationSettings.DEFAULT_SEND_HOUR : settings.getSendHour(),
                NotificationSettingsCodec.parseOffsets(settings.getReminderOffsets()),
                kinds,
                NotificationSettingsCodec.parseSubscriptions(settings.getPushSubscriptions()),
                muted,
                mutedLoans,
                sender.isConfigured());
    }

    static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 2000) {
            throw new ValidationException("A push endpoint is required");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Push endpoint is not a valid URL");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if ((!https && !localHttp) || uri.getHost() == null) {
            throw new ValidationException("Push endpoint must be an https URL");
        }
    }

    private static void validateKey(String value, int expectedBytes, String name) {
        try {
            byte[] decoded = WebPushCrypto.base64UrlDecode(value == null ? "" : value);
            if (decoded.length != expectedBytes) {
                throw new ValidationException("Subscription key '" + name + "' has the wrong length");
            }
            if (expectedBytes == 65) {
                WebPushCrypto.decodePublicKey(decoded);
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Subscription key '" + name + "' is not valid base64url");
        }
    }
}
