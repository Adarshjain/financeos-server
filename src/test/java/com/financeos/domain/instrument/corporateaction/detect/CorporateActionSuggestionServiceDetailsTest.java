package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CorporateActionSchemeDetails;
import com.financeos.api.instrument.dto.CorporateActionSuggestionResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The suggestion's own answer carries the stored notice reading, or none. */
class CorporateActionSuggestionServiceDetailsTest {

    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final DemergerCandidates candidates = mock(DemergerCandidates.class);
    private final SchemeDetailsLookup lookup = mock(SchemeDetailsLookup.class);
    private final CorporateActionSuggestionService service = new CorporateActionSuggestionService(suggestions,
            mock(CorporateActionRepository.class), mock(CorporateActionService.class), candidates, lookup);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CorporateActionSuggestion demerger() {
        UserContext.setCurrentUserId(userId);
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName("Acme Industries Limited");
        i.setSymbol("ACME");
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(i);
        s.setKind(SuggestionKind.demerger);
        s.setStatus(SuggestionStatus.pending);
        s.setExDate(LocalDate.of(2025, 3, 10));
        when(suggestions.findOwnedById(s.getId(), userId)).thenReturn(Optional.of(s));
        when(candidates.forSuggestion(s)).thenReturn(List.of());
        return s;
    }

    @Test
    void getCarriesTheDetailsWhenRead() {
        CorporateActionSuggestion s = demerger();
        CorporateActionSchemeDetails details = new CorporateActionSchemeDetails(List.of(), null, null, null, List.of("CHILD_NOT_NAMED"),
                Instant.parse("2025-03-20T10:00:00Z"), List.of());
        when(lookup.forSuggestions(any())).thenReturn(Map.of(s.getId(), details));

        CorporateActionSuggestionResponse out = service.get(s.getId());

        assertThat(out.details()).isSameAs(details);
    }

    @Test
    void getHasNullDetailsWhenNothingWasRead() {
        CorporateActionSuggestion s = demerger();
        when(lookup.forSuggestions(any())).thenReturn(Map.of());

        assertThat(service.get(s.getId()).details()).isNull();
    }
}
