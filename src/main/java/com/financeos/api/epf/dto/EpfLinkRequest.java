package com.financeos.api.epf.dto;

import java.util.UUID;

public record EpfLinkRequest(UUID transactionId, boolean recordTds) {
}
