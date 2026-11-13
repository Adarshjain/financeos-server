package com.financeos.api.instrument.dto;

import java.time.LocalDate;
import org.springframework.lang.Nullable;

/**
 * Where a value in {@link CorporateActionSchemeDetails} was read: the company's notice on the exchange.
 *
 * @param url   the notice's file on the exchange's archive
 * @param date  the notice's date; null when the exchange gave none
 * @param quote the sentence the value was read from, at most 300 characters
 */
public record CorporateActionSchemeSource(String url, @Nullable LocalDate date, String quote) {
}
