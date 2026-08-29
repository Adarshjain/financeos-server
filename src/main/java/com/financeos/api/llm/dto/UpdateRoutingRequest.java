package com.financeos.api.llm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateRoutingRequest(
        @NotEmpty List<@Valid RoutingEntryRequest> entries
) {}
