package com.financeos.api.instrument.dto;

import java.util.List;

/** One page of an instrument's price history, newest first, with the total over every page. */
public record PriceHistoryPage(List<InstrumentPriceResponse> items, int page, int size, long totalElements,
                               int totalPages) {
}
