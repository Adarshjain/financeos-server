package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionReason;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** One detected corporate action: what was found, why it is held (if it is), and the action it created (if any). */
public record CorporateActionSuggestionResponse(
        UUID id,
        SuggestionKind kind,
        SuggestionStatus status,
        @Nullable SuggestionReason reason,
        LocalDate exDate,
        @Nullable Integer ratioFrom,
        @Nullable Integer ratioTo,
        @Nullable String subject,
        InstrumentRef instrument,
        @Nullable UUID corporateActionId
) {
    public record InstrumentRef(UUID id, String name, String symbol) {
    }

    public static CorporateActionSuggestionResponse from(CorporateActionSuggestion s) {
        return new CorporateActionSuggestionResponse(s.getId(), s.getKind(), s.getStatus(), s.getReason(), s.getExDate(),
                s.getRatioFrom(), s.getRatioTo(), s.getSubject(),
                new InstrumentRef(s.getInstrument().getId(), s.getInstrument().getName(), s.getInstrument().getSymbol()),
                s.getCorporateAction() == null ? null : s.getCorporateAction().getId());
    }
}
