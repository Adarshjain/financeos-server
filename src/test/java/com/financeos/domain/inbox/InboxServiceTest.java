package com.financeos.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.collect.InboxCollector;
import com.financeos.domain.inbox.collect.InboxRows;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InboxServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private static final String ACT = InboxItemResponse.SECTION_ACT_NOW;
    private static final String LOOK = InboxItemResponse.SECTION_NEEDS_LOOK;
    private static final String INFO = InboxItemResponse.SECTION_INFO;
    private static final String CRIT = InboxItemResponse.SEVERITY_CRITICAL;
    private static final String WARN = InboxItemResponse.SEVERITY_WARNING;
    private static final String SEV_INFO = InboxItemResponse.SEVERITY_INFO;

    private final UUID userId = UUID.randomUUID();
    private InboxCollector first;
    private InboxCollector second;
    private InboxItemStateRepository repository;
    private InboxService service;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        first = mock(InboxCollector.class);
        second = mock(InboxCollector.class);
        repository = mock(InboxItemStateRepository.class);
        when(first.collect(any(), any())).thenReturn(List.of());
        when(second.collect(any(), any())).thenReturn(List.of());
        when(repository.findByUserId(userId)).thenReturn(List.of());
        when(repository.findByUserIdAndItemKey(any(), any())).thenReturn(Optional.empty());
        service = new InboxService(List.of(first, second), repository);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static InboxItemResponse item(String key, String section, String severity, LocalDate date, String title) {
        return InboxRows.item(key, InboxKinds.BILL, severity, section, title, null, "/x", null, date, List.of(), InboxRefsResponse.NONE);
    }

    private static InboxItemResponse summaryRow(String key, String section, int count) {
        return InboxRows.summary(key, InboxKinds.REVIEW, WARN, section, "Review", null, "/transactions/review", count, List.of());
    }

    private static List<String> keys(InboxResponse response) {
        List<String> keys = new ArrayList<>();
        for (InboxItemResponse i : response.items()) {
            keys.add(i.key());
        }
        return keys;
    }

    private InboxItemState state(String key) {
        InboxItemState s = new InboxItemState(userId, key);
        return s;
    }

    // ---------------------------------------------------------------- list: aggregation

    @Test
    void listMergesEveryCollectorsRowsAndAsksEachForTheUserAndBusinessToday() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        when(second.collect(userId, TODAY)).thenReturn(List.of(item("b", LOOK, WARN, null, "B")));

        InboxResponse response = service.list(userId);

        assertEquals(List.of("a", "b"), keys(response));
        verify(first).collect(userId, TODAY);
        verify(second).collect(userId, TODAY);
        assertNotNull(response.generatedAt());
    }

    @Test
    void anEmptyInboxHasZeroCounts() {
        InboxResponse response = service.list(userId);
        assertTrue(response.items().isEmpty());
        assertEquals(InboxSummaryResponse.EMPTY, response.summary());
    }

    @Test
    void listIsReadOnly() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        service.list(userId);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    // ---------------------------------------------------------------- list: state

    @Test
    void aDismissedRowIsHidden() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A"), item("b", ACT, CRIT, null, "B")));
        InboxItemState dismissed = state("a");
        dismissed.setDismissedAt(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(List.of(dismissed));

        assertEquals(List.of("b"), keys(service.list(userId)));
    }

    @Test
    void aSnoozedRowIsHiddenWhileTodayIsBeforeTheSnoozeDate() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        InboxItemState snoozed = state("a");
        snoozed.setSnoozedUntil(TODAY.plusDays(1));
        when(repository.findByUserId(userId)).thenReturn(List.of(snoozed));

        assertTrue(service.list(userId).items().isEmpty());
    }

    @Test
    void aSnoozedRowReturnsOnTheSnoozeDate() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        InboxItemState snoozed = state("a");
        snoozed.setSnoozedUntil(TODAY);
        when(repository.findByUserId(userId)).thenReturn(List.of(snoozed));

        assertEquals(List.of("a"), keys(service.list(userId)));
    }

    @Test
    void aSnoozeThatHasPassedNoLongerHides() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        InboxItemState snoozed = state("a");
        snoozed.setSnoozedUntil(TODAY.minusDays(2));
        when(repository.findByUserId(userId)).thenReturn(List.of(snoozed));

        assertEquals(List.of("a"), keys(service.list(userId)));
    }

    @Test
    void stateForAKeyNoCollectorProducesHasNoEffect() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        InboxItemState stale = state("gone");
        stale.setDismissedAt(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(List.of(stale));

        assertEquals(List.of("a"), keys(service.list(userId)));
    }

    @Test
    void hiddenRowsAreLeftOutOfTheCounts() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A"), item("b", LOOK, WARN, null, "B")));
        InboxItemState dismissed = state("a");
        dismissed.setDismissedAt(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(List.of(dismissed));

        assertEquals(new InboxSummaryResponse(0, 1, 0, 1), service.list(userId).summary());
    }

    @Test
    void aDismissedSummaryRowIsHidden() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(summaryRow(InboxKinds.KEY_REVIEW, LOOK, 12)));
        InboxItemState dismissed = state(InboxKinds.KEY_REVIEW);
        dismissed.setDismissedAt(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(List.of(dismissed));

        InboxResponse response = service.list(userId);
        assertTrue(response.items().isEmpty());
        assertEquals(InboxSummaryResponse.EMPTY, response.summary());
    }

    // ---------------------------------------------------------------- list: order

    @Test
    void rowsAreOrderedBySectionFirst() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("info", INFO, CRIT, null, "A"),
                item("look", LOOK, CRIT, null, "A"),
                item("act", ACT, SEV_INFO, null, "A")));

        assertEquals(List.of("act", "look", "info"), keys(service.list(userId)));
    }

    @Test
    void withinASectionTheWorstSeverityComesFirst() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("info", ACT, SEV_INFO, TODAY, "A"),
                item("warn", ACT, WARN, TODAY, "A"),
                item("crit", ACT, CRIT, TODAY.plusDays(5), "A")));

        assertEquals(List.of("crit", "warn", "info"), keys(service.list(userId)));
    }

    @Test
    void withinASeverityTheSoonestDateComesFirstAndUndatedRowsLast() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("undated", ACT, CRIT, null, "A"),
                item("later", ACT, CRIT, TODAY.plusDays(3), "A"),
                item("overdue", ACT, CRIT, TODAY.minusDays(2), "A")));

        assertEquals(List.of("overdue", "later", "undated"), keys(service.list(userId)));
    }

    @Test
    void sameDateRowsAreOrderedByTitleIgnoringCaseWithUntitledLast() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("none", ACT, CRIT, TODAY, null),
                item("zeta", ACT, CRIT, TODAY, "Zeta"),
                item("alpha", ACT, CRIT, TODAY, "alpha"),
                item("beta", ACT, CRIT, TODAY, "Beta")));

        assertEquals(List.of("alpha", "beta", "zeta", "none"), keys(service.list(userId)));
    }

    @Test
    void rowsFromDifferentCollectorsInterleaveByUrgency() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("f-info", INFO, SEV_INFO, null, "A")));
        when(second.collect(userId, TODAY)).thenReturn(List.of(item("s-act", ACT, CRIT, null, "A")));

        assertEquals(List.of("s-act", "f-info"), keys(service.list(userId)));
    }

    @Test
    void anUnknownSectionOrSeveritySortsLast() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("odd-section", "elsewhere", CRIT, null, "A"),
                item("info", INFO, SEV_INFO, null, "A"),
                item("odd-severity", INFO, "loud", null, "A")));

        assertEquals(List.of("info", "odd-severity", "odd-section"), keys(service.list(userId)));
    }

    // ---------------------------------------------------------------- summary

    @Test
    void summaryCountsRowsPerSectionAndBadgeIsActNowPlusNeedsLook() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(
                item("a1", ACT, CRIT, null, "A"),
                item("a2", ACT, WARN, null, "A"),
                item("l1", LOOK, WARN, null, "A"),
                item("i1", INFO, SEV_INFO, null, "A"),
                item("i2", INFO, SEV_INFO, null, "A"),
                item("i3", INFO, SEV_INFO, null, "A")));

        assertEquals(new InboxSummaryResponse(2, 1, 3, 3), service.list(userId).summary());
    }

    @Test
    void aSummaryRowCountsOnceWhateverItsCount() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(summaryRow(InboxKinds.KEY_REVIEW, LOOK, 40)));

        assertEquals(new InboxSummaryResponse(0, 1, 0, 1), service.list(userId).summary());
    }

    @Test
    void anUnknownSectionCountsAsInfoAndNotTowardsTheBadge() {
        assertEquals(new InboxSummaryResponse(0, 0, 1, 0),
                InboxService.summarise(List.of(item("x", "elsewhere", CRIT, null, "A"))));
    }

    @Test
    void summaryOnlyIsTheListsSummary() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A"), item("b", INFO, SEV_INFO, null, "B")));

        assertEquals(new InboxSummaryResponse(1, 0, 1, 1), service.summaryOnly(userId));
    }

    // ---------------------------------------------------------------- snooze

    @Test
    void snoozeCreatesStateUntilTheDate() {
        service.snooze(userId, "bill:abc", TODAY.plusDays(1));

        ArgumentCaptor<InboxItemState> saved = ArgumentCaptor.forClass(InboxItemState.class);
        verify(repository).save(saved.capture());
        assertEquals(userId, saved.getValue().getUserId());
        assertEquals("bill:abc", saved.getValue().getItemKey());
        assertEquals(TODAY.plusDays(1), saved.getValue().getSnoozedUntil());
        assertEquals(null, saved.getValue().getDismissedAt());
    }

    @Test
    void snoozingAgainUpdatesTheSameStateRow() {
        InboxItemState existing = state("bill:abc");
        existing.setSnoozedUntil(TODAY.plusDays(1));
        when(repository.findByUserIdAndItemKey(userId, "bill:abc")).thenReturn(Optional.of(existing));

        service.snooze(userId, "bill:abc", TODAY.plusDays(10));

        ArgumentCaptor<InboxItemState> saved = ArgumentCaptor.forClass(InboxItemState.class);
        verify(repository).save(saved.capture());
        assertSame(existing, saved.getValue());
        assertEquals(TODAY.plusDays(10), existing.getSnoozedUntil());
    }

    @Test
    void snoozeUntilTodayIsRejected() {
        assertThrows(ValidationException.class, () -> service.snooze(userId, "bill:abc", TODAY));
        verify(repository, never()).save(any());
    }

    @Test
    void snoozeUntilAPastDateIsRejected() {
        assertThrows(ValidationException.class, () -> service.snooze(userId, "bill:abc", TODAY.minusDays(1)));
        verify(repository, never()).save(any());
    }

    @Test
    void snoozeWithoutADateIsRejected() {
        assertThrows(ValidationException.class, () -> service.snooze(userId, "bill:abc", null));
        verify(repository, never()).save(any());
    }

    @Test
    void summaryRowsCannotBeSnoozed() {
        ValidationException review = assertThrows(ValidationException.class,
                () -> service.snooze(userId, InboxKinds.KEY_REVIEW, TODAY.plusDays(1)));
        assertEquals("This row can be dismissed but not snoozed", review.getMessage());
        assertThrows(ValidationException.class, () -> service.snooze(userId, InboxKinds.KEY_GMAIL_ATTENTION, TODAY.plusDays(1)));
        verify(repository, never()).save(any());
    }

    @Test
    void snoozeValidatesTheKey() {
        assertThrows(ValidationException.class, () -> service.snooze(userId, " ", TODAY.plusDays(1)));
        verifyNoInteractions(repository);
    }

    // ---------------------------------------------------------------- dismiss

    @Test
    void dismissCreatesADismissedState() {
        service.dismiss(userId, "bill:abc");

        ArgumentCaptor<InboxItemState> saved = ArgumentCaptor.forClass(InboxItemState.class);
        verify(repository).save(saved.capture());
        assertEquals(userId, saved.getValue().getUserId());
        assertEquals("bill:abc", saved.getValue().getItemKey());
        assertNotNull(saved.getValue().getDismissedAt());
    }

    @Test
    void summaryRowsCanBeDismissed() {
        service.dismiss(userId, InboxKinds.KEY_REVIEW);
        service.dismiss(userId, InboxKinds.KEY_GMAIL_ATTENTION);
        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void dismissingASnoozedRowKeepsItsSnoozeAndAddsTheDismissal() {
        InboxItemState existing = state("bill:abc");
        existing.setSnoozedUntil(TODAY.plusDays(3));
        when(repository.findByUserIdAndItemKey(userId, "bill:abc")).thenReturn(Optional.of(existing));

        service.dismiss(userId, "bill:abc");

        verify(repository).save(existing);
        assertNotNull(existing.getDismissedAt());
        assertEquals(TODAY.plusDays(3), existing.getSnoozedUntil());
    }

    @Test
    void dismissingAgainKeepsTheFirstDismissalTime() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        InboxItemState existing = state("bill:abc");
        existing.setDismissedAt(first);
        when(repository.findByUserIdAndItemKey(userId, "bill:abc")).thenReturn(Optional.of(existing));

        service.dismiss(userId, "bill:abc");

        assertEquals(first, existing.getDismissedAt());
    }

    @Test
    void dismissValidatesTheKey() {
        assertThrows(ValidationException.class, () -> service.dismiss(userId, ""));
        verifyNoInteractions(repository);
    }

    // ---------------------------------------------------------------- undo

    @Test
    void clearStateDeletesTheRowsState() {
        InboxItemState existing = state("bill:abc");
        existing.setDismissedAt(Instant.now());
        when(repository.findByUserIdAndItemKey(userId, "bill:abc")).thenReturn(Optional.of(existing));

        service.clearState(userId, "bill:abc");

        verify(repository).delete(existing);
    }

    @Test
    void clearStateWithNoStateIsANoOp() {
        service.clearState(userId, "bill:abc");
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    void clearStateValidatesTheKey() {
        assertThrows(ValidationException.class, () -> service.clearState(userId, null));
        verifyNoInteractions(repository);
    }

    @Test
    void afterUndoTheRowIsListedAgain() {
        when(first.collect(userId, TODAY)).thenReturn(List.of(item("a", ACT, CRIT, null, "A")));
        InboxItemState dismissed = state("a");
        dismissed.setDismissedAt(Instant.now());
        when(repository.findByUserId(userId)).thenReturn(List.of(dismissed));
        when(repository.findByUserIdAndItemKey(userId, "a")).thenReturn(Optional.of(dismissed));
        assertTrue(service.list(userId).items().isEmpty());

        service.clearState(userId, "a");
        verify(repository).delete(dismissed);
        when(repository.findByUserId(userId)).thenReturn(List.of());

        assertEquals(List.of("a"), keys(service.list(userId)));
    }

    // ---------------------------------------------------------------- key validation

    @Test
    void aNullKeyIsRejected() {
        ValidationException e = assertThrows(ValidationException.class, () -> InboxService.validateKey(null));
        assertEquals("Inbox item key is required", e.getMessage());
    }

    @Test
    void aBlankKeyIsRejected() {
        assertEquals("Inbox item key is required",
                assertThrows(ValidationException.class, () -> InboxService.validateKey("")).getMessage());
        assertEquals("Inbox item key is required",
                assertThrows(ValidationException.class, () -> InboxService.validateKey("   ")).getMessage());
    }

    @Test
    void aKeyAtTheMaximumLengthIsAccepted() {
        InboxService.validateKey("k".repeat(InboxItemState.MAX_KEY_LENGTH));
    }

    @Test
    void aKeyOverTheMaximumLengthIsRejected() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> InboxService.validateKey("k".repeat(InboxItemState.MAX_KEY_LENGTH + 1)));
        assertEquals("Inbox item key is too long", e.getMessage());
    }

    @Test
    void aKeyWithWhitespaceIsMalformed() {
        assertEquals("Inbox item key is malformed",
                assertThrows(ValidationException.class, () -> InboxService.validateKey("bill: abc")).getMessage());
        assertEquals("Inbox item key is malformed",
                assertThrows(ValidationException.class, () -> InboxService.validateKey("bill:abc\t")).getMessage());
    }

    @Test
    void aKeyWithAControlCharacterIsMalformed() {
        assertEquals("Inbox item key is malformed",
                assertThrows(ValidationException.class, () -> InboxService.validateKey("bill:\u0000abc")).getMessage());
    }

    @Test
    void everyRealKeyShapeIsAccepted() {
        UUID id = UUID.randomUUID();
        InboxService.validateKey(InboxKinds.billKey(id));
        InboxService.validateKey(InboxKinds.billAwaitingKey(id));
        InboxService.validateKey(InboxKinds.emiKey(id, 12));
        InboxService.validateKey(InboxKinds.lendingKey(id));
        InboxService.validateKey(InboxKinds.statementExpectedKey(id, TODAY));
        InboxService.validateKey(InboxKinds.gmailReconnectKey(id));
        InboxService.validateKey(InboxKinds.jobKey(id));
        InboxService.validateKey(InboxKinds.rewardMilestoneKey(id, TODAY));
        InboxService.validateKey(InboxKinds.rewardCapKey(id, null));
        InboxService.validateKey(InboxKinds.KEY_REVIEW);
        InboxService.validateKey(InboxKinds.KEY_GMAIL_ATTENTION);
    }
}
