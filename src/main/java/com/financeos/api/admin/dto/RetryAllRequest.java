package com.financeos.api.admin.dto;

import jakarta.validation.constraints.NotNull;

public record RetryAllRequest(@NotNull Kind kind) {
    public enum Kind { gmail, jobs }
}
