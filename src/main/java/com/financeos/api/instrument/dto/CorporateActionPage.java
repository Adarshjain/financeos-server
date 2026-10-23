package com.financeos.api.instrument.dto;

import java.util.List;

/** One page of the caller's corporate actions with the total over every page. */
public record CorporateActionPage(List<CorporateActionRowResponse> items, int page, int size, long totalElements,
                                  int totalPages) {
}
