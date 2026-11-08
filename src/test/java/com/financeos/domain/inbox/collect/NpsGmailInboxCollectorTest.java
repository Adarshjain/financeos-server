package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.nps.NpsSector;
import com.financeos.domain.nps.gmail.NpsGmailDetections;
import com.financeos.domain.nps.gmail.NpsGmailDetections.Detection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpsGmailInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private static NpsGmailDetections detections(UUID userId, int locked, Detection... found) {
        NpsGmailDetections detections = mock(NpsGmailDetections.class);
        when(detections.lockedCount(userId)).thenReturn(locked);
        when(detections.forUser(userId)).thenReturn(List.of(found));
        return detections;
    }

    @Test
    void oneRowPerDetectedPranAndOneGroupedPasswordRow() {
        UUID userId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Detection d = new Detection(key, "5678", "Asha Rao", 3, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31),
                NpsSector.ALL_CITIZEN);

        List<InboxItemResponse> rows = new NpsGmailInboxCollector(detections(userId, 2, d)).collect(userId, TODAY);

        assertEquals(2, rows.size());
        InboxItemResponse p = rows.get(0);
        assertEquals("nps_password", p.kind());
        assertEquals(InboxKinds.npsPasswordKey(2), p.key());
        assertEquals("2 NPS statements need a password", p.title());
        assertEquals(InboxItemResponse.SECTION_NEEDS_LOOK, p.section());
        assertEquals("/investments/nps", p.href());
        assertEquals(List.of("nps_password", "dismiss"), p.actions().stream().map(a -> a.type()).toList());

        InboxItemResponse a = rows.get(1);
        assertEquals("nps_new_account", a.kind());
        assertEquals(InboxKinds.npsPendingKey("nps_new_account", key), a.key());
        assertEquals("NPS account found in Gmail (••5678, Asha Rao)", a.title());
        assertEquals("3 statements · Jan 2025 – Mar 2025", a.subtitle());
        assertEquals(key, a.refs().npsPendingId());
        assertEquals(LocalDate.of(2025, 3, 31), a.date());
        assertEquals("/investments/nps", a.href());
        assertEquals(List.of("nps_add_self", "nps_add_child", "nps_ignore"), a.actions().stream().map(x -> x.type()).toList());
        assertTrue(InboxKinds.ALL.contains(InboxKinds.NPS_PASSWORD) && InboxKinds.ALL.contains(InboxKinds.NPS_NEW_ACCOUNT));
    }

    @Test
    void aSingleLockedStatementAndAMissingHolderReadNaturally() {
        UUID userId = UUID.randomUUID();
        Detection d = new Detection(UUID.randomUUID(), "1234", null, 1, null, null, null);

        List<InboxItemResponse> rows = new NpsGmailInboxCollector(detections(userId, 1, d)).collect(userId, TODAY);

        assertEquals("1 NPS statement needs a password", rows.get(0).title());
        assertEquals("NPS account found in Gmail (••1234)", rows.get(1).title());
        assertEquals("1 statement", rows.get(1).subtitle());
    }

    @Test
    void nothingHeldMeansNoRows() {
        UUID userId = UUID.randomUUID();

        assertTrue(new NpsGmailInboxCollector(detections(userId, 0)).collect(userId, TODAY).isEmpty());
    }
}
