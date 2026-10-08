package com.financeos.api.notification;

import com.financeos.api.notification.dto.MuteAccountRequest;
import com.financeos.api.notification.dto.MuteLoanRequest;
import com.financeos.api.notification.dto.NotificationEvaluateResponse;
import com.financeos.api.notification.dto.NotificationSettingsResponse;
import com.financeos.api.notification.dto.PushPublicKeyResponse;
import com.financeos.api.notification.dto.PushSubscriptionRequest;
import com.financeos.api.notification.dto.PushTestResponse;
import com.financeos.api.notification.dto.RemovePushSubscriptionRequest;
import com.financeos.api.notification.dto.UpdateNotificationSettingsRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.NotificationScheduler;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.push.WebPushSender;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Notification preferences, Web Push device registration, per-card / per-loan mutes and an on-demand tick. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationSettingsService settingsService;
    private final WebPushSender sender;
    private final NotificationScheduler scheduler;

    public NotificationController(NotificationSettingsService settingsService, WebPushSender sender,
                                  NotificationScheduler scheduler) {
        this.settingsService = settingsService;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping("/settings")
    public ResponseEntity<NotificationSettingsResponse> getSettings() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(settingsService.view(userId)));
    }

    @PutMapping("/settings")
    public ResponseEntity<NotificationSettingsResponse> updateSettings(@Valid @RequestBody UpdateNotificationSettingsRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(settingsService.update(userId,
                request.pushEnabled(), request.sendHour(), request.reminderOffsets(), request.kinds())));
    }

    @GetMapping("/push/public-key")
    public ResponseEntity<PushPublicKeyResponse> getPublicKey() {
        requireCurrentUserId();
        return ResponseEntity.ok(new PushPublicKeyResponse(sender.publicKey(), sender.isConfigured()));
    }

    @PostMapping("/push/subscriptions")
    public ResponseEntity<NotificationSettingsResponse> subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(settingsService.addSubscription(userId,
                request.endpoint(), request.keys().p256dh(), request.keys().auth(), request.userAgent())));
    }

    /** POST rather than DELETE: the endpoint URL travels in the body (too long and too opaque for a path). */
    @PostMapping("/push/subscriptions/remove")
    public ResponseEntity<NotificationSettingsResponse> unsubscribe(@Valid @RequestBody RemovePushSubscriptionRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(settingsService.removeSubscription(userId, request.endpoint())));
    }

    @PostMapping("/push/test")
    public ResponseEntity<PushTestResponse> sendTest() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(new PushTestResponse(settingsService.sendTest(userId)));
    }

    @PutMapping("/accounts/{accountId}/mute")
    public ResponseEntity<NotificationSettingsResponse> muteAccount(@PathVariable UUID accountId,
                                                                    @Valid @RequestBody MuteAccountRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(
                settingsService.setAccountMuted(userId, accountId, request.muted())));
    }

    @PutMapping("/loans/{loanId}/mute")
    public ResponseEntity<NotificationSettingsResponse> muteLoan(@PathVariable UUID loanId,
                                                                 @Valid @RequestBody MuteLoanRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(NotificationSettingsResponse.from(
                settingsService.setLoanMuted(userId, loanId, request.muted())));
    }

    /**
     * Runs every producer for the caller right now — exactly what the hourly tick does for them.
     * Idempotent through the producers' markers, so calling it twice sends nothing extra.
     */
    @PostMapping("/evaluate")
    public ResponseEntity<NotificationEvaluateResponse> evaluate() {
        UUID userId = requireCurrentUserId();
        NotificationScheduler.Summary summary = scheduler.runProducers(userId);
        return ResponseEntity.ok(new NotificationEvaluateResponse(
                summary.evaluated(), summary.recorded(), summary.sent(), summary.failed()));
    }
}
