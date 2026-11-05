package com.financeos.api.nps.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many held Gmail statements the retry queued for import. */
@Schema(name = "NpsGmailRetry")
public record NpsGmailRetryResponse(int importing) {
}
