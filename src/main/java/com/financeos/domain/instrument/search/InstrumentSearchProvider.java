package com.financeos.domain.instrument.search;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.domain.instrument.InstrumentType;

import java.util.List;

public interface InstrumentSearchProvider {
    boolean supports(InstrumentType type); // null type = supports all
    List<InstrumentCandidate> search(String query, InstrumentType type);
}
