package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.notification.NotificationController;
import com.financeos.api.notification.dto.NotificationSettingsResponse;
import com.financeos.api.notification.dto.UpdateNotificationSettingsRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.notification.push.WebPushSender;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The reminderStyle setting: default, storage, validation, the GET/PUT DTOs and the prefs helpers. */
class ReminderStyleTest {

    private final UserNotificationSettingsRepository repository = mock(UserNotificationSettingsRepository.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final NotificationSettingsService service =
            new NotificationSettingsService(repository, mock(AccountRepository.class), mock(LoanRepository.class), sender);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(userId)).thenReturn(Optional.empty());
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void defaultsToStandardWithoutARow() {
        assertEquals(ReminderStyle.STANDARD, service.view(userId).reminderStyle());
        assertEquals(ReminderStyle.STANDARD, ReminderStyle.parse(null));
        assertEquals(ReminderStyle.STANDARD, ReminderStyle.parse("loud"));
        assertEquals(ReminderStyle.QUIET, ReminderStyle.parse(" quiet "));
    }

    @Test
    void setReminderStyleStoresItCaseInsensitivelyAndTheViewShowsIt() {
        NotificationSettingsService.View view = service.setReminderStyle(userId, "quiet");

        assertEquals(ReminderStyle.QUIET, view.reminderStyle());
    }

    @Test
    void anUnknownStyleIsRejected() {
        assertThrows(ValidationException.class, () -> service.setReminderStyle(userId, "LOUD"));
        assertThrows(ValidationException.class, () -> service.setReminderStyle(userId, null));
    }

    @Test
    void prefsKnowWhenTheUserIsQuietAndNarrowTheOffsets() {
        UserNotificationSettings quiet = new UserNotificationSettings(userId);
        quiet.setReminderStyle("QUIET");
        NotificationPrefs q = new NotificationPrefs(quiet, true, 9, List.of(7, 3, 1, 0), NotificationSettingsCodec.parseKinds(null));
        assertTrue(q.quiet());
        assertEquals(List.of(1), q.reminderOffsets());

        NotificationPrefs standard = new NotificationPrefs(new UserNotificationSettings(userId), true, 9, List.of(7, 3), NotificationSettingsCodec.parseKinds(null));
        assertFalse(standard.quiet());
        assertEquals(List.of(7, 3), standard.reminderOffsets());
        assertFalse(new NotificationPrefs(null, false, 9, List.of(0), NotificationSettingsCodec.parseKinds(null)).quiet());
    }

    @Test
    void putWithAStyleSetsItAndGetShowsItAndPutWithoutOneLeavesItAlone() {
        NotificationSettingsService mocked = mock(NotificationSettingsService.class);
        NotificationController controller = new NotificationController(mocked, sender, mock(NotificationScheduler.class));
        NotificationSettingsService.View standard = new NotificationSettingsService.View(true, 9, List.of(1),
                Map.of(NotificationKind.BILL_OVERDUE, true), List.of(), List.of(), List.of(), true, ReminderStyle.STANDARD);
        NotificationSettingsService.View quiet = new NotificationSettingsService.View(true, 9, List.of(1),
                Map.of(NotificationKind.BILL_OVERDUE, true), List.of(), List.of(), List.of(), true, ReminderStyle.QUIET);
        when(mocked.update(userId, null, null, null, null)).thenReturn(standard);
        when(mocked.setReminderStyle(userId, "QUIET")).thenReturn(quiet);
        when(mocked.view(userId)).thenReturn(quiet);

        NotificationSettingsResponse put = controller.updateSettings(new UpdateNotificationSettingsRequest(null, null, null, null, "QUIET")).getBody();
        assertEquals("QUIET", put.reminderStyle());
        assertEquals("QUIET", controller.getSettings().getBody().reminderStyle());

        controller.updateSettings(new UpdateNotificationSettingsRequest(null, null, null, null, null));
        verify(mocked, org.mockito.Mockito.times(1)).setReminderStyle(any(), any());
        verify(mocked, never()).setReminderStyle(userId, null);
    }

    @Test
    void aBadStyleOnPutIs400AndSavesNothingElse() {
        NotificationSettingsService mocked = mock(NotificationSettingsService.class);
        NotificationController controller = new NotificationController(mocked, sender, mock(NotificationScheduler.class));

        assertThrows(ValidationException.class,
                () -> controller.updateSettings(new UpdateNotificationSettingsRequest(true, 7, null, null, "LOUD")));

        verify(mocked, never()).update(any(), any(), any(), any(), any());
        verify(mocked, never()).setReminderStyle(any(), any());
    }
}
