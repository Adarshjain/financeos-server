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
 * user can receive {@link NotificationKind#ACCOUNTS_FOUND}). Sent only when a run marked new suggestions:
 * "We found N accounts" / "Add them to see your transactions.". A run that found nothing says nothing.
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

    static String title(int count) {
        return count == 1 ? "We found 1 account" : "We found " + count + " accounts";
    }

    static final String BODY = "Add them to see your transactions.";

    /** Never throws: a notification problem must not fail the job. */
    public void notifyFound(UUID userId, int count, boolean firstScanCompleted, Instant ranAt) {
        if (count <= 0) return;   // nothing found, first scan or not: say nothing
        String title = title(count);
        try {
            feedService.record(userId, AppNotificationKind.ACCOUNTS_FOUND, title, BODY, AppNotificationRefs.NONE,
                    "account-suggestions:" + ranAt.toEpochMilli());
            NotificationPrefs prefs = prefsLoader.load(userId);
            if (prefs.deliverable(NotificationKind.ACCOUNTS_FOUND)) {
                int sent = settingsService.deliver(prefs.settings(), new PushMessage(title, BODY, URL, TAG));
                log.info("Account suggestions notification: count={}, firstScanCompleted={}, devices={}", count, firstScanCompleted, sent);
            }
        } catch (Exception e) {
            log.warn("Account suggestions notification failed: {}", e.getClass().getSimpleName());
        }
    }
}
