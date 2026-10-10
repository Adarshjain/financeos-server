package com.financeos.api.instrument.dto;

import java.util.List;

/**
 * One page of GET /instruments: the instruments as the caller sees them (their own name / type
 * overrides applied, filtered and sorted on those), with the total over every page.
 *
 * @param page          the page number, from 0
 * @param size          the page size asked for (after clamping to 1..200)
 * @param totalElements every matching instrument, over all pages
 * @param totalPages    pages of {@code size} needed for {@code totalElements} (0 when none match)
 */
public record InstrumentListPage(List<InstrumentResponse> items, int page, int size, long totalElements,
                                 int totalPages) {
}
