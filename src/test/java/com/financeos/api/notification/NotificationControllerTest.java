package com.financeos.api.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.notification.dto.MuteAccountRequest;
import com.financeos.api.notification.dto.MuteLoanRequest;
import com.financeos.api.notification.dto.NotificationEvaluateResponse;
import com.financeos.api.notification.dto.NotificationSettingsResponse;
import com.financeos.api.notification.dto.PushPublicKeyResponse;
import com.financeos.api.notification.dto.PushSubscriptionRequest;
import com.financeos.api.notification.dto.RemovePushSubscriptionRequest;
import com.financeos.api.notification.dto.UpdateNotificationSettingsRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationScheduler;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.push.PushSubscription;
import com.financeos.domain.notification.push.WebPushSender;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NotificationControllerTest {

    private NotificationSettingsService service;
    private WebPushSender sender;
    private NotificationScheduler scheduler;
    private NotificationController controller;
    private final UUID userId = UUID.randomUUID();
    private NotificationSettingsService.View view;

    @BeforeEach
    void setUp() {
        service = mock(NotificationSettingsService.class);
        sender = mock(WebPushSender.class);
        scheduler = mock(NotificationScheduler.class);
        controller = new NotificationController(service, sender, scheduler);
        UserContext.setCurrentUserId(userId);
        view = new NotificationSettingsService.View(true, 9, List.of(7, 3, 1, 0),
                Map.of(NotificationKind.BILL_OVERDUE, false, NotificationKind.BILL_DUE_REMINDER, true, NotificationKind.STATEMENT_RECEIVED, true),
                List.of(new PushSubscription("https://push/1", "k", "a", "Chrome", Instant.parse("2026-10-08T05:00:00Z"))),
                List.of(UUID.randomUUID()), List.of(UUID.randomUUID()), true);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void settingsResponseFlattensTheView() {
        when(service.view(userId)).thenReturn(view);
        NotificationSettingsResponse body = controller.getSettings().getBody();
        assertTrue(body.pushEnabled());
        assertEquals(9, body.sendHour());
        assertEquals(Boolean.FALSE, body.kinds().get("BILL_OVERDUE"));
        assertEquals(1, body.devices().size());
        assertEquals("https://push/1", body.devices().get(0).endpoint());
        assertEquals(1, body.mutedAccountIds().size());
        assertEquals(1, body.mutedLoanIds().size());
        assertTrue(body.pushConfigured());
    }

    @Test
    void loanMuteDelegatesWithTheCurrentUser() {
        UUID loanId = UUID.randomUUID();
        when(service.setLoanMuted(userId, loanId, true)).thenReturn(view);
        NotificationSettingsResponse body = controller.muteLoan(loanId, new MuteLoanRequest(true)).getBody();
        verify(service).setLoanMuted(userId, loanId, true);
        assertEquals(1, body.mutedLoanIds().size());
    }

    @Test
    void evaluateRunsEveryProducerForTheCallerAndReportsTheSummary() {
        when(scheduler.runProducers(userId)).thenReturn(new NotificationScheduler.Summary(1, 4, 2, 1, 1));
        NotificationEvaluateResponse body = controller.evaluate().getBody();
        assertEquals(new NotificationEvaluateResponse(4, 2, 1, 1), body);
    }

    @Test
    void writesDelegateWithTheCurrentUser() {
        when(service.update(userId, false, 20, List.of(3), Map.of("BILL_OVERDUE", false))).thenReturn(view);
        controller.updateSettings(new UpdateNotificationSettingsRequest(false, 20, List.of(3), Map.of("BILL_OVERDUE", false)));
        verify(service).update(userId, false, 20, List.of(3), Map.of("BILL_OVERDUE", false));

        when(service.addSubscription(userId, "https://push/1", "k", "a", "Chrome")).thenReturn(view);
        controller.subscribe(new PushSubscriptionRequest("https://push/1", new PushSubscriptionRequest.Keys("k", "a"), "Chrome"));
        verify(service).addSubscription(userId, "https://push/1", "k", "a", "Chrome");

        when(service.removeSubscription(userId, "https://push/1")).thenReturn(view);
        controller.unsubscribe(new RemovePushSubscriptionRequest("https://push/1"));
        verify(service).removeSubscription(userId, "https://push/1");

        UUID accountId = UUID.randomUUID();
        when(service.setAccountMuted(userId, accountId, true)).thenReturn(view);
        controller.muteAccount(accountId, new MuteAccountRequest(true));
        verify(service).setAccountMuted(userId, accountId, true);

        when(service.sendTest(userId)).thenReturn(2);
        assertEquals(2, controller.sendTest().getBody().sent());
    }

    @Test
    void publicKeyComesFromTheSender() {
        when(sender.publicKey()).thenReturn("BPub");
        when(sender.isConfigured()).thenReturn(true);
        PushPublicKeyResponse body = controller.getPublicKey().getBody();
        assertEquals("BPub", body.publicKey());
        assertTrue(body.configured());
    }

    @Test
    void unauthenticatedCallsAreRejected() {
        UserContext.clear();
        assertThrows(ResponseStatusException.class, () -> controller.getSettings());
        assertThrows(ResponseStatusException.class, () -> controller.getPublicKey());
        assertThrows(ResponseStatusException.class, () -> controller.evaluate());
        assertThrows(ResponseStatusException.class, () -> controller.muteLoan(UUID.randomUUID(), new MuteLoanRequest(true)));
    }
}
