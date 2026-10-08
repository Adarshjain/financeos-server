package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.notification.push.PushSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationSettingsCodecTest {

    @Test
    void offsetsParseDescendingDistinctAndFallBackToDefaults() {
        assertEquals(List.of(7, 3, 1, 0), NotificationSettingsCodec.parseOffsets(null));
        assertEquals(List.of(7, 3, 1, 0), NotificationSettingsCodec.parseOffsets("  "));
        assertEquals(List.of(14, 2), NotificationSettingsCodec.parseOffsets("2, 14,2"));
        assertEquals(List.of(7, 3, 1, 0), NotificationSettingsCodec.parseOffsets("x,y"));
        assertEquals(List.of(7, 3, 1, 0), NotificationSettingsCodec.parseOffsets("-5"));
        assertEquals("10,3,0", NotificationSettingsCodec.writeOffsets(List.of(3, 10, 0, 3)));
    }

    @Test
    void kindsDefaultOnAndStoredOverridesWin() {
        Map<NotificationKind, Boolean> defaults = NotificationSettingsCodec.parseKinds(null);
        assertEquals(NotificationKind.values().length, defaults.size());
        assertTrue(defaults.values().stream().allMatch(Boolean::booleanValue));

        Map<NotificationKind, Boolean> parsed = NotificationSettingsCodec.parseKinds(
                "{\"BILL_OVERDUE\":false,\"RETIRED_KIND\":true,\"STATEMENT_RECEIVED\":null}");
        assertFalse(parsed.get(NotificationKind.BILL_OVERDUE));
        assertTrue(parsed.get(NotificationKind.STATEMENT_RECEIVED));
        assertTrue(parsed.get(NotificationKind.BILL_DUE_REMINDER));

        assertTrue(NotificationSettingsCodec.parseKinds("not json").get(NotificationKind.BILL_OVERDUE));

        String written = NotificationSettingsCodec.writeKinds(parsed);
        assertEquals(parsed, NotificationSettingsCodec.parseKinds(written));
    }

    @Test
    void subscriptionsRoundTripAndEmptyWritesNull() {
        assertTrue(NotificationSettingsCodec.parseSubscriptions(null).isEmpty());
        assertTrue(NotificationSettingsCodec.parseSubscriptions("garbage").isEmpty());
        assertNull(NotificationSettingsCodec.writeSubscriptions(List.of()));

        PushSubscription sub = new PushSubscription("https://push.example/abc", "p256", "auth", "Chrome", Instant.parse("2026-10-08T05:00:00Z"));
        String json = NotificationSettingsCodec.writeSubscriptions(List.of(sub));
        List<PushSubscription> back = NotificationSettingsCodec.parseSubscriptions(json);
        assertEquals(1, back.size());
        assertEquals(sub, back.get(0));
    }

    @Test
    void unknownSubscriptionFieldsAreIgnored() {
        List<PushSubscription> back = NotificationSettingsCodec.parseSubscriptions(
                "[{\"endpoint\":\"https://e\",\"p256dh\":\"k\",\"auth\":\"a\",\"expirationTime\":null}]");
        assertEquals(1, back.size());
        assertEquals("https://e", back.get(0).endpoint());
    }
}
