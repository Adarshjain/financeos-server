package com.financeos.api.gmail.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record RescanRequest(
    @NotNull LocalDate fromDate
) {}
