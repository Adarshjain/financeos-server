package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.AttentionReason;
import jakarta.validation.constraints.NotNull;

public record AttentionBulkRequest(@NotNull AttentionReason reason) {}
