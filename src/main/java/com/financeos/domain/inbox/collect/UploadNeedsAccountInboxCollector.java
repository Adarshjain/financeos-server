package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.ingestion.held.HeldUpload;
import com.financeos.domain.ingestion.held.HeldUploadRepository;
import com.financeos.domain.inbox.InboxKinds;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Statement files uploaded without an account that could not be matched to one. One row per file; it
 * clears when the file is assigned or discarded, so it offers snooze but no dismiss.
 */
@Component
public class UploadNeedsAccountInboxCollector implements InboxCollector {

    static final String HREF = "/transactions/import";

    private final HeldUploadRepository heldUploadRepository;

    public UploadNeedsAccountInboxCollector(HeldUploadRepository heldUploadRepository) {
        this.heldUploadRepository = heldUploadRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        for (HeldUpload h : heldUploadRepository.findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(userId)) {
            String subtitle = h.getDetectedBankId() == null || h.getDetectedBankId().isBlank()
                    ? h.getFileName()
                    : h.getFileName() + " · " + h.getDetectedBankId();
            String href = HREF + "?held=" + h.getId();
            rows.add(InboxRows.item(InboxKinds.uploadNeedsAccountKey(h.getId()), InboxKinds.UPLOAD_NEEDS_ACCOUNT,
                    SEVERITY_WARNING, SECTION_ACT_NOW, "A statement needs an account", subtitle, href, null, null,
                    List.of(InboxRows.open(href), InboxRows.snooze()),
                    InboxRefsResponse.ofJob(h.getJobId())));
        }
        return rows;
    }
}
