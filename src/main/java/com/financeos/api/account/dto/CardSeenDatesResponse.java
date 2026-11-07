package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.time.LocalDate;

/** When a card number shows up in the user's mail and in the account's transactions; dates null when never seen. */
public record CardSeenDatesResponse(@Nullable LocalDate firstSeen, @Nullable LocalDate lastSeen, int mailCount, int transactionCount) {}
