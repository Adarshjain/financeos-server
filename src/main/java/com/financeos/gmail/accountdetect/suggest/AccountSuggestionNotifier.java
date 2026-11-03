package com.financeos.gmail.accountdetect.suggest;

import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.notification.push.PushMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The one user-facing signal of a background account-detect run: an in-app feed entry (always) and a push (when the
 * user can receive {@link NotificationKind#ACCOUNTS_FOUND}). Sent when the run marked new suggestions, and always on the run that completes the first scan (the progress card
 * promises it), with the "Done finding your accounts" title.
 */
@Component
public class AccountSuggestionNotifier {

    private static final Logger log = LoggerFactory.getLogger(AccountSuggestionNotifier.class);
    static final String URL = "/accounts";
    static final String TAG = "account-suggestions";

    private final NotificationFeedService feedService;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public AccountSuggestionNotifier(NotificationFeedService feedService, NotificationPrefsLoader prefsLoader,
                                     NotificationSettingsService settingsService) {
        this.feedService = feedService;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    static String title(int count, boolean firstScanCompleted) {
        if (firstScanCompleted) return count == 0 ? "Done finding your accounts: none found" : "Done finding your accounts: " + count + " found";
        return count == 1 ? "Found 1 account or card in your email" : "Found " + count + " accounts and cards in your email";
    }

    /** Never throws: a notification problem must not fail the job. */
    public void notifyFound(UUID userId, int count, boolean firstScanCompleted, Instant ranAt) {
        if (count <= 0 && !firstScanCompleted) return;
        String title = title(count, firstScanCompleted);
        try {
            feedService.record(userId, AppNotificationKind.ACCOUNTS_FOUND, title, null, AppNotificationRefs.NONE,
                    "account-suggestions:" + ranAt.toEpochMilli());
            NotificationPrefs prefs = prefsLoader.load(userId);
            if (prefs.deliverable(NotificationKind.ACCOUNTS_FOUND)) {
                int sent = settingsService.deliver(prefs.settings(), new PushMessage(title, "", URL, TAG));
                log.info("Account suggestions notification: count={}, firstScanCompleted={}, devices={}", count, firstScanCompleted, sent);
            }
        } catch (Exception e) {
            log.warn("Account suggestions notification failed: {}", e.getClass().getSimpleName());
        }
    }
}
