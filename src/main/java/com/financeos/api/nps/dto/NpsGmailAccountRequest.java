package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import org.springframework.lang.Nullable;

/**
 * The go-ahead on a detected PRAN (add it as the user's own or their child's). A null {@code sector} takes the
 * guess (All Citizen when the statements came through eNPS, otherwise none).
 */
public record NpsGmailAccountRequest(
        NpsRelation relation,
        @Nullable NpsSector sector) {
}
