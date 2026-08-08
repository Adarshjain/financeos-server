package com.financeos.api.lending.dto;

public record UpdateCounterpartyRequest(
        String name,
        String notes
) {}
