package com.financeos.api.nps.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The go-ahead on a detected PRAN: the account that was created and how many held statements are importing. */
@Schema(name = "NpsGmailDetectionAccepted")
public record NpsGmailDetectionAcceptedResponse(UUID accountId, int importing) {
}
