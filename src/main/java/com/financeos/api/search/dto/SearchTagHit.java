package com.financeos.api.search.dto;

import java.util.UUID;

/** One of the user's tags with how many of the user's transactions carry it. */
public record SearchTagHit(UUID id, String name, long transactionCount) {
}
