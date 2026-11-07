package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.AttentionReason;
import jakarta.validation.constraints.NotNull;

/** {@code ungroupedOnly} (default false) limits the action to mail that is in no group (see GET /gmail/attention/ungrouped). */
public record AttentionBulkRequest(@NotNull AttentionReason reason, boolean ungroupedOnly) {

    public AttentionBulkRequest(AttentionReason reason) {
        this(reason, false);
    }
}
