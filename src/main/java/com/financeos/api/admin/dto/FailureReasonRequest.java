package com.financeos.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailureReasonRequest(@NotBlank @Size(max = 500) String reason) {}
