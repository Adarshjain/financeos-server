package com.financeos.api.transaction.dto;

public record BatchFailure(
    String id,
    String reason
) {}
