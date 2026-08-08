package com.financeos.api.obligations.dto;

import java.util.List;

public record ObligationsResponse(
        List<ObligationItemDto> items
) {}
