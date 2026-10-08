package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GmailInboxCollectorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final GmailConnectionRepository connectionRepository = mock(GmailConnectionRepository.class);
    private final GmailProcessedMessageRepository processedRepository = mock(GmailProcessedMessageRepository.class);
    private final GmailInboxCollector collector = new GmailInboxCollector(connectionRepository, processedRepository);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        when(connectionRepository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static GmailConnection connection(String email, Instant authFailedAt) {
        GmailConnection c = new GmailConnection();
        c.setId(UUID.randomUUID());
        c.setEmail(email);
        c.setAuthFailedAt(authFailedAt);
        return c;
    }

    private void attention(long count) {
        when(processedRepository.countByUserIdAndStatusIn(userId, GmailAttentionNotificationService.ATTENTION_STATUSES)).thenReturn(count);
    }

    @Test
    void eachMailboxGoogleRejectedGetsItsOwnReconnectRowDatedInTheBusinessZone() {
        // 20:00 UTC on 18 Oct is already 19 Oct in IST.
        GmailConnection personal = connection("me@gmail.com", Instant.parse("2026-10-18T20:00:00Z"));
        GmailConnection work = connection("work@gmail.com", Instant.parse("2026-10-10T05:00:00Z"));
        when(connectionRepository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)).thenReturn(List.of(personal, work));
        attention(0);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(2, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("gmail-reconnect:" + personal.getId(), row.key());
        assertEquals("gmail_reconnect", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Reconnect Gmail", row.title());
        assertEquals("me@gmail.com stopped syncing · Google rejected its token", row.subtitle());
        assertEquals("/settings/gmail", row.href());
        assertEquals(null, row.amount());
        assertEquals(LocalDate.of(2026, 10, 19), row.date());
        assertEquals(List.of(InboxActionResponse.navigate("reconnect", "Reconnect", "/settings/gmail")), row.actions(),
                "a reconnect cannot be snoozed away");
        assertEquals(InboxRefsResponse.ofConnection(personal.getId()), row.refs());
        assertEquals("gmail-reconnect:" + work.getId(), rows.get(1).key());
        assertEquals("work@gmail.com stopped syncing · Google rejected its token", rows.get(1).subtitle());
    }

    @Test
    void emailsWaitingOnTheUserBecomeOneSummaryRowCountingAllOfThemNotJustUnnotifiedOnes() {
        attention(3);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("gmail-attention", row.key());
        assertEquals("gmail_attention", row.kind());
        assertEquals(InboxItemResponse.ROW_SUMMARY, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("needs_look", row.section());
        assertEquals("3 emails need attention", row.title());
        assertEquals("Unmatched account, not opted in, or failed to import", row.subtitle());
        assertEquals("/settings/gmail?focus=attention", row.href());
        assertEquals(3, row.count());
        assertEquals(List.of(InboxRows.open("/settings/gmail?focus=attention")), row.actions());
        verify(processedRepository, never()).findUnnotifiedAttentionItems(any(), any());
    }

    @Test
    void oneEmailIsSingularAndZeroEmailsMeansNoRow() {
        attention(1);
        assertEquals("1 email needs attention", collector.collect(userId, TODAY).get(0).title());

        attention(0);
        assertEquals(List.of(), collector.collect(userId, TODAY));
    }

    @Test
    void aCountBeyondIntRangeIsClampedInsteadOfOverflowing() {
        attention(Integer.MAX_VALUE + 5L);
        assertEquals(Integer.MAX_VALUE, collector.collect(userId, TODAY).get(0).count());
    }

    @Test
    void reconnectRowsComeBeforeTheAttentionSummary() {
        GmailConnection personal = connection("me@gmail.com", Instant.parse("2026-10-18T05:00:00Z"));
        when(connectionRepository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)).thenReturn(List.of(personal));
        attention(2);

        assertEquals(List.of("gmail-reconnect:" + personal.getId(), "gmail-attention"),
                collector.collect(userId, TODAY).stream().map(InboxItemResponse::key).toList());
    }
}
