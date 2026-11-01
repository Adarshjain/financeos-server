package com.financeos.api.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TagRequest(
        @NotBlank(message = "Tag name is required") @Size(min = 1, max = 100, message = "Tag name must be between 1 and 100 characters") String name) {
}
