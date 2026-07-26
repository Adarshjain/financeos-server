package com.financeos.api.investment.dto;

import java.util.List;

public record PositionsResponse(
        List<PositionDto> positions
) {
}
