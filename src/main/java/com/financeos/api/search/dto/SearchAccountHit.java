package com.financeos.api.search.dto;

import com.financeos.domain.account.AccountType;

import java.util.UUID;

/** An account matched by name, bank or card last4; {@code last4} is a card's, null when it has none. */
public record SearchAccountHit(UUID id, String name, String bank, AccountType type, String last4) {
}
