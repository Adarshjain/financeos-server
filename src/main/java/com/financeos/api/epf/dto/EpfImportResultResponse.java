package com.financeos.api.epf.dto;

import java.util.UUID;

public record EpfImportResultResponse(UUID employerId, String employerName, int imported, int replaced, int skipped) {
}
