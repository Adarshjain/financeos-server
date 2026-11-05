package com.financeos.api.nps.dto;

import java.util.UUID;

/** The bank or card transaction an NPS contribution is paid by (or is not). */
public record NpsLinkRequest(UUID transactionId) {
}
