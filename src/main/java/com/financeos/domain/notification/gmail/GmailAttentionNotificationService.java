package com.financeos.domain.notification.gmail;

import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "N emails need attention": one digest per tick covering every attention item (unmatched
 * account, account not opted in, permanent failure) the user has not been told about, after the
 * send hour. The marker is {@code attention_notified_at} on each item; a retry clears it so a
 * second failure is announced again. Items advance even when nothing can be sent.
 */
@Service
public class GmailAttentionNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(GmailAttentionNotificationService.class);

    /** The same statuses the /gmail/attention endpoint lists by default. */
    public static final List<GmailProcessedStatus> ATTENTION_STATUSES = List.of(
            GmailProcessedStatus.UNRESOLVED_ACCOUNT,
            GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN,
            GmailProcessedStatus.FAILED_PERMANENT);

    private final GmailProcessedMessageRepository processedMessageRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public GmailAttentionNotificationService(GmailProcessedMessageRepository processedMessageRepository,
                                             NotificationPrefsLoader prefsLoader,
                                             NotificationSettingsService settingsService) {
        this.processedMessageRepository = processedMessageRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "gmail-attention";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        NotificationPrefs prefs = prefsLoader.load(userId);
        if (!prefs.pastSendHour(AppTime.now())) {
            return NotificationOutcome.NONE;
        }
        List<GmailProcessedMessage> items = processedMessageRepository.findUnnotifiedAttentionItems(userId, ATTENTION_STATUSES);
        if (items.isEmpty()) {
            return NotificationOutcome.NONE;
        }
        int sent = 0;
        if (prefs.deliverable(NotificationKind.GMAIL_ATTENTION)) {
            sent = settingsService.deliver(prefs.settings(), GmailMessages.attention(items));
            log.info("Gmail attention digest: items={}, devices={}", items.size(), sent);
        }
        Instant now = AppTime.now().atZone(AppTime.zone()).toInstant();
        for (GmailProcessedMessage item : items) {
            item.setAttentionNotifiedAt(now);
        }
        processedMessageRepository.saveAll(items);
        return new NotificationOutcome(items.size(), 1, sent);
    }
}
