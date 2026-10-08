package com.financeos.api.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record RemovePushSubscriptionRequest(@NotBlank String endpoint) {
}
