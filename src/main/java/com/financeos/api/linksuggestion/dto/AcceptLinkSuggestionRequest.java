package com.financeos.api.linksuggestion.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AcceptLinkSuggestionRequest(@NotNull UUID candidateId) {}
