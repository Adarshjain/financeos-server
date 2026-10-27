package com.financeos.api.epf.dto;

import java.util.List;

/** Several passbooks previewed together: one section per member ID, plus the files that could not be used. */
public record EpfImportBatchPreviewResponse(List<EpfImportSection> sections, List<EpfImportRefusedFile> refused) {

    /** Files of one member ID (or one file without a member ID) merged into a single preview. */
    public record EpfImportSection(String key, List<String> fileNames, EpfImportPreviewResponse preview) {
    }
}
