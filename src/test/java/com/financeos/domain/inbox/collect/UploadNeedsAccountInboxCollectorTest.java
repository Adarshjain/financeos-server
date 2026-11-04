package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.ingestion.held.HeldUpload;
import com.financeos.domain.ingestion.held.HeldUploadRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadNeedsAccountInboxCollectorTest {

    @Test
    void oneActNowRowPerHeldFileWithItsNameAndBank() {
        UUID userId = UUID.randomUUID();
        HeldUpload h = new HeldUpload();
        h.setId(UUID.randomUUID());
        h.setJobId(UUID.randomUUID());
        h.setFileName("march.pdf");
        h.setDetectedBankId("hdfc");
        HeldUpload noBank = new HeldUpload();
        noBank.setId(UUID.randomUUID());
        noBank.setJobId(UUID.randomUUID());
        noBank.setFileName("locked.pdf");
        HeldUploadRepository repository = mock(HeldUploadRepository.class);
        when(repository.findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of(h, noBank));

        List<InboxItemResponse> rows = new UploadNeedsAccountInboxCollector(repository).collect(userId, LocalDate.of(2026, 11, 4));

        assertEquals(2, rows.size());
        InboxItemResponse first = rows.get(0);
        assertEquals(InboxKinds.uploadNeedsAccountKey(h.getId()), first.key());
        assertEquals("upload_needs_account", first.kind());
        assertEquals(InboxItemResponse.SECTION_ACT_NOW, first.section());
        assertEquals("A statement needs an account", first.title());
        assertEquals("march.pdf · hdfc", first.subtitle());
        assertEquals("locked.pdf", rows.get(1).subtitle());
        assertTrue(InboxKinds.ALL.contains(InboxKinds.UPLOAD_NEEDS_ACCOUNT));
    }

    @Test
    void nothingHeldMeansNoRows() {
        UUID userId = UUID.randomUUID();
        HeldUploadRepository repository = mock(HeldUploadRepository.class);
        when(repository.findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertTrue(new UploadNeedsAccountInboxCollector(repository).collect(userId, LocalDate.of(2026, 11, 4)).isEmpty());
    }
}
