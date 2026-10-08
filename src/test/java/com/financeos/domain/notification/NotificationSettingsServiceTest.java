package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.financeos.domain.user.User;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationSettingsServiceTest {

    private UserNotificationSettingsRepository repository;
    private AccountRepository accountRepository;
    private LoanRepository loanRepository;
    private WebPushSender sender;
    private NotificationSettingsService service;
    private final UUID userId = UUID.randomUUID();
    private final String p256dh = WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
    private final String auth = WebPushCrypto.base64Url(new byte[16]);

    @BeforeEach
    void setUp() {
        repository = mock(UserNotificationSettingsRepository.class);
        accountRepository = mock(AccountRepository.class);
        sender = mock(WebPushSender.class);
        loanRepository = mock(LoanRepository.class);
        service = new NotificationSettingsService(repository, accountRepository, loanRepository, sender);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(userId)).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        when(sender.isConfigured()).thenReturn(true);
    }

    private UserNotificationSettings stored() {
        UserNotificationSettings s = new UserNotificationSettings(userId);
        when(repository.findById(userId)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void viewWithoutARowIsTheDefaults() {
        NotificationSettingsService.View view = service.view(userId);
        assertTrue(view.pushEnabled());
        assertEquals(9, view.sendHour());
        assertEquals(List.of(7, 3, 1, 0), view.reminderOffsets());
        assertTrue(view.kinds().values().stream().allMatch(Boolean::booleanValue));
        assertTrue(view.subscriptions().isEmpty());
        assertTrue(view.mutedAccountIds().isEmpty());
        assertTrue(view.pushConfigured());
        verify(repository, never()).save(any());
    }

    @Test
    void updateCreatesTheRowAndValidatesEachField() {
        NotificationSettingsService.View view = service.update(userId, false, 20, List.of(1, 10, 1), Map.of("BILL_OVERDUE", false));
        assertFalse(view.pushEnabled());
        assertEquals(20, view.sendHour());
        assertEquals(List.of(10, 1), view.reminderOffsets());
        assertFalse(view.kinds().get(NotificationKind.BILL_OVERDUE));
        assertTrue(view.kinds().get(NotificationKind.BILL_DUE_REMINDER));
        verify(repository, times(2)).save(any());

        assertThrows(ValidationException.class, () -> service.update(userId, null, 24, null, null));
        assertThrows(ValidationException.class, () -> service.update(userId, null, -1, null, null));
        assertThrows(ValidationException.class, () -> service.update(userId, null, null, List.of(), null));
        assertThrows(ValidationException.class, () -> service.update(userId, null, null, List.of(61), null));
        assertThrows(ValidationException.class, () -> service.update(userId, null, null, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), null));
        assertThrows(ValidationException.class, () -> service.update(userId, null, null, null, Map.of("NOPE", true)));
    }

    @Test
    void partialUpdateLeavesOtherFieldsAlone() {
        UserNotificationSettings s = stored();
        s.setSendHour(18);
        s.setKindsJson("{\"BILL_OVERDUE\":false}");
        NotificationSettingsService.View view = service.update(userId, null, null, List.of(5), Map.of("STATEMENT_RECEIVED", false));
        assertEquals(18, view.sendHour());
        assertEquals(List.of(5), view.reminderOffsets());
        assertFalse(view.kinds().get(NotificationKind.BILL_OVERDUE));
        assertFalse(view.kinds().get(NotificationKind.STATEMENT_RECEIVED));
    }

    @Test
    void addSubscriptionValidatesDedupesAndCaps() {
        assertThrows(ValidationException.class, () -> service.addSubscription(userId, "http://push.example/x", p256dh, auth, null));
        assertThrows(ValidationException.class, () -> service.addSubscription(userId, "not a url", p256dh, auth, null));
        assertThrows(ValidationException.class, () -> service.addSubscription(userId, "https://push.example/x", "AAAA", auth, null));
        assertThrows(ValidationException.class, () -> service.addSubscription(userId, "https://push.example/x", p256dh, "AAAA", null));
        assertThrows(ValidationException.class, () -> service.addSubscription(userId, "https://push.example/x", p256dh, "***", null));

        NotificationSettingsService.View view = service.addSubscription(userId, "https://push.example/x", p256dh, auth, "Chrome/1");
        assertEquals(1, view.subscriptions().size());
        assertEquals("Chrome/1", view.subscriptions().get(0).userAgent());

        UserNotificationSettings s = stored();
        s.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(view.subscriptions()));
        view = service.addSubscription(userId, "https://push.example/x", p256dh, auth, "x".repeat(500));
        assertEquals(1, view.subscriptions().size(), "same endpoint replaces");
        assertEquals(200, view.subscriptions().get(0).userAgent().length());

        view = service.addSubscription(userId, "http://localhost:9999/dev", p256dh, auth, null);
        assertEquals(2, view.subscriptions().size(), "plain http is fine for localhost");

        for (int i = 0; i < 12; i++) {
            s.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(view.subscriptions()));
            view = service.addSubscription(userId, "https://push.example/" + i, p256dh, auth, null);
        }
        assertEquals(10, view.subscriptions().size());
        assertEquals("https://push.example/11", view.subscriptions().get(9).endpoint());
        assertTrue(view.subscriptions().stream().noneMatch(sub -> sub.endpoint().equals("https://push.example/x")), "oldest dropped");
    }

    @Test
    void removeSubscriptionIsIdempotent() {
        assertTrue(service.removeSubscription(userId, "https://push.example/x").subscriptions().isEmpty());
        UserNotificationSettings s = stored();
        s.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(List.of(
                new PushSubscription("https://push.example/x", p256dh, auth, null, Instant.now()),
                new PushSubscription("https://push.example/y", p256dh, auth, null, Instant.now()))));
        NotificationSettingsService.View view = service.removeSubscription(userId, "https://push.example/x");
        assertEquals(List.of("https://push.example/y"), view.subscriptions().stream().map(PushSubscription::endpoint).toList());
        assertEquals(1, service.removeSubscription(userId, "https://push.example/none").subscriptions().size());
    }

    @Test
    void muteChecksOwnershipAndFlipsTheFlag() {
        User owner = new User();
        owner.setId(userId);
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUser(owner);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));

        NotificationSettingsService.View view = service.setAccountMuted(userId, account.getId(), true);
        assertEquals(List.of(account.getId()), view.mutedAccountIds());
        assertTrue(account.getNotificationsMuted());
        assertTrue(service.setAccountMuted(userId, account.getId(), false).mutedAccountIds().isEmpty());

        assertThrows(ValidationException.class, () -> service.setAccountMuted(UUID.randomUUID(), account.getId(), true));
        assertThrows(ResourceNotFoundException.class, () -> service.setAccountMuted(userId, UUID.randomUUID(), true));
    }

    @Test
    void loanMuteChecksOwnershipAndFlipsTheFlag() {
        User owner = new User();
        owner.setId(userId);
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUser(owner);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.findByUser_IdAndNotificationsMutedTrue(userId)).thenAnswer(inv ->
                Boolean.TRUE.equals(loan.getNotificationsMuted()) ? List.of(loan) : List.of());

        NotificationSettingsService.View view = service.setLoanMuted(userId, loan.getId(), true);
        assertEquals(List.of(loan.getId()), view.mutedLoanIds());
        assertTrue(loan.getNotificationsMuted());
        assertTrue(view.mutedAccountIds().isEmpty(), "loan mutes and card mutes are separate lists");
        assertTrue(service.setLoanMuted(userId, loan.getId(), false).mutedLoanIds().isEmpty());
        assertFalse(loan.getNotificationsMuted());

        assertThrows(ValidationException.class, () -> service.setLoanMuted(UUID.randomUUID(), loan.getId(), true));
        assertThrows(ResourceNotFoundException.class, () -> service.setLoanMuted(userId, UUID.randomUUID(), true));
    }

    @Test
    void testPushRequiresConfigurationAndADevice() {
        when(sender.isConfigured()).thenReturn(false);
        assertThrows(ValidationException.class, () -> service.sendTest(userId));
        when(sender.isConfigured()).thenReturn(true);
        assertThrows(ValidationException.class, () -> service.sendTest(userId));
        stored();
        assertThrows(ValidationException.class, () -> service.sendTest(userId));
    }

    @Test
    void deliverCountsAcceptedAndPrunesGoneSubscriptions() {
        UserNotificationSettings s = stored();
        PushSubscription ok = new PushSubscription("https://push.example/ok", p256dh, auth, null, Instant.now());
        PushSubscription gone = new PushSubscription("https://push.example/gone", p256dh, auth, null, Instant.now());
        PushSubscription flaky = new PushSubscription("https://push.example/flaky", p256dh, auth, null, Instant.now());
        s.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(List.of(ok, gone, flaky)));
        PushMessage message = new PushMessage("t", "b", "/", "tag");
        when(sender.send(any(), any())).thenAnswer(inv -> {
            PushSubscription target = inv.getArgument(0);
            return switch (target.endpoint()) {
                case "https://push.example/ok" -> new WebPushSender.SendResult(201, true, false);
                case "https://push.example/gone" -> new WebPushSender.SendResult(410, false, true);
                default -> new WebPushSender.SendResult(500, false, false);
            };
        });

        assertEquals(1, service.deliver(s, message));

        List<PushSubscription> remaining = NotificationSettingsCodec.parseSubscriptions(s.getPushSubscriptions());
        assertEquals(List.of("https://push.example/ok", "https://push.example/flaky"),
                remaining.stream().map(PushSubscription::endpoint).toList());
        verify(repository).save(s);

        assertEquals(1, service.sendTest(userId));
    }

    @Test
    void deliverWithNothingRegisteredOrPushOffIsANoOp() {
        UserNotificationSettings s = new UserNotificationSettings(userId);
        assertEquals(0, service.deliver(s, new PushMessage("t", "b", "/", "tag")));
        s.setPushSubscriptions(NotificationSettingsCodec.writeSubscriptions(List.of(
                new PushSubscription("https://push.example/ok", p256dh, auth, null, Instant.now()))));
        when(sender.isConfigured()).thenReturn(false);
        assertEquals(0, service.deliver(s, new PushMessage("t", "b", "/", "tag")));
        verify(sender, never()).send(any(), any());
        assertNull(new UserNotificationSettings(userId).getPushSubscriptions());
    }
}
