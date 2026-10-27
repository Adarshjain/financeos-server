package com.financeos.api.epf.dto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.lang.Nullable;

import java.time.LocalDate;

public record EpfEmployerRequest(
        @NotBlank String name,
        @Nullable String memberId,
        @Nullable LocalDate joinedOn,
        @Nullable LocalDate leftOn) {
}
