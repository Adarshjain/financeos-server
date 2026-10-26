package com.financeos.api.search.dto;

import java.util.UUID;

/** A dashboard matched by name. */
public record SearchDashboardHit(UUID id, String name, boolean isDefault) {
}
