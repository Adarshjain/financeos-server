package com.financeos.api.accountdetect.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** "Is this your …": the candidate account and the user's answer. */
public record IsThisRequest(@NotNull UUID accountId, @NotNull Boolean yes) {}
