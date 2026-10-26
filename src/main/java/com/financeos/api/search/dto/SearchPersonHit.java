package com.financeos.api.search.dto;

import java.util.UUID;

/** A lending counterparty matched by name. */
public record SearchPersonHit(UUID id, String name) {
}
