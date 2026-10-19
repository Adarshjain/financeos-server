package com.financeos.api.account.dto;

/** Counts shown on an account page's tabs. {@code cards} excludes closed cardholders. */
public record AccountTabCountsResponse(long statements, long cards, long transactions) {
}
