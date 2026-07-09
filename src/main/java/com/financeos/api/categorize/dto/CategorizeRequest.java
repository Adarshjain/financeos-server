package com.financeos.api.categorize.dto;

import jakarta.validation.constraints.NotBlank;

public record CategorizeRequest(@NotBlank String description) {}
