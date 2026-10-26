package com.financeos.api.search.dto;

import java.util.UUID;

/** A loan matched by name, lender or loan account number. */
public record SearchLoanHit(UUID id, String name, String lender) {
}
