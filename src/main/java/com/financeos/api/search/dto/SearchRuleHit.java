package com.financeos.api.search.dto;

import java.util.UUID;

/** One of the user's category rules, matched on its match text or display name. */
public record SearchRuleHit(UUID id, String matchText, String categoryName) {
}
