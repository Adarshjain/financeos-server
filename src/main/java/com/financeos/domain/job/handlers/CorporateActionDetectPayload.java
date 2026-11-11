package com.financeos.domain.job.handlers;

import java.util.UUID;

/** CORPORATE_ACTION_DETECT payload: the user to check, or null for the nightly run over every stock holder. */
public record CorporateActionDetectPayload(UUID userId) {
}
