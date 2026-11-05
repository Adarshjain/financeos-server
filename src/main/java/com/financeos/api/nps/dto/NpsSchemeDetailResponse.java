package com.financeos.api.nps.dto;

import java.util.List;

/** One scheme with the user's holding of it per account and tier, and their entries touching it (newest first). */
public record NpsSchemeDetailResponse(
        NpsSchemeResponse scheme,
        List<NpsSummaryResponse.Holding> holdings,
        List<NpsEntryResponse> entries) {
}
