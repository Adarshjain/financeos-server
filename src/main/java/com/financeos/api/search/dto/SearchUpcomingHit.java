package com.financeos.api.search.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A tracked recurring series (a bill, subscription or other regular payment) from Upcoming; kind is SUBSCRIPTION, EMI or OTHER. */
public record SearchUpcomingHit(UUID id, String name, LocalDate nextDueOn, BigDecimal amount, String kind) {
}
